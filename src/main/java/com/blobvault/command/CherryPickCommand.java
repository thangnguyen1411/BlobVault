package com.blobvault.command;

import com.blobvault.model.CommitObject;
import com.blobvault.model.IndexEntry;
import com.blobvault.model.ObjectType;
import com.blobvault.service.CherryPicker;
import com.blobvault.service.CommitResolver;
import com.blobvault.service.CommitSerializer;
import com.blobvault.service.TreeWriter;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.Index;
import com.blobvault.storage.RefManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Applies the changes introduced by an existing commit onto the current HEAD.
 * Equivalent to: git cherry-pick {@literal <commit>}
 *
 * Modes:
 *   cherry-pick {@literal <expr>}   — apply the given commit
 *   cherry-pick --continue          — resume after resolving conflicts
 *   cherry-pick --abort             — abandon the cherry-pick and restore HEAD
 */
public class CherryPickCommand implements Command {

    @Override
    public String name() { return "cherry-pick"; }

    @Override
    public String usage() { return "cherry-pick <commit> | --continue | --abort"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: blobvault " + usage());
            return;
        }

        BlobStore store = new BlobStore(cwd);
        RefManager refs = new RefManager(cwd);
        Index index = new Index(cwd);

        switch (args[1]) {
            case "--continue" -> doContinue(cwd, store, refs, index);
            case "--abort"    -> doAbort(cwd, store, refs, index);
            default           -> doCherryPick(cwd, args[1], store, refs, index);
        }
    }

    // -------------------------------------------------------------------------
    // Normal cherry-pick
    // -------------------------------------------------------------------------

    private void doCherryPick(Path cwd, String expr, BlobStore store,
                               RefManager refs, Index index) throws Exception {
        // Refuse if a cherry-pick is already in progress
        if (refs.readStateFile("CHERRY_PICK_HEAD") != null) {
            System.err.println("Cherry-pick already in progress.");
            System.err.println("  (use 'blobvault cherry-pick --continue' or '--abort')");
            return;
        }

        CommitResolver resolver = new CommitResolver(refs, store);
        String commitHash = resolver.resolve(expr);
        if (commitHash == null) {
            System.err.println("Cannot resolve '" + expr + "' to a commit.");
            return;
        }

        String origHead = refs.resolveHead();

        CherryPicker picker = new CherryPicker(cwd, store, refs, index);
        String newHash = picker.cherryPick(commitHash, true);

        if (newHash != null) {
            // Success
            CommitObject newCommit = CommitSerializer.deserialize(store.read(newHash));
            String branch = refs.currentBranch();
            String branchLabel = (branch != null) ? branch : "(detached HEAD)";
            String firstLine = newCommit.message().lines().findFirst().orElse("");
            System.out.println("[" + branchLabel + "] " + newHash.substring(0, 7) + " " + firstLine);
        } else {
            // Conflict — write state files so --continue / --abort work
            refs.writeStateFile("CHERRY_PICK_HEAD", commitHash);
            if (origHead != null) {
                refs.writeStateFile("ORIG_HEAD", origHead);
            }
            System.out.println("CONFLICT: Cherry-pick of " + commitHash.substring(0, 7) + " failed.");
            System.out.println("Resolve the conflicts and then run:");
            System.out.println("  blobvault cherry-pick --continue");
            System.out.println("Or to abort:");
            System.out.println("  blobvault cherry-pick --abort");
        }
    }

    // -------------------------------------------------------------------------
    // --continue
    // -------------------------------------------------------------------------

    private void doContinue(Path cwd, BlobStore store, RefManager refs,
                             Index index) throws Exception {
        String cherryPickHead = refs.readStateFile("CHERRY_PICK_HEAD");
        if (cherryPickHead == null) {
            System.err.println("No cherry-pick in progress.");
            return;
        }

        // Read the original commit's message and append the trailer
        CommitObject originalCommit = CommitSerializer.deserialize(store.read(cherryPickHead));
        String message = originalCommit.message()
                + "\n\n(cherry picked from commit " + cherryPickHead + ")";

        // Build tree from current index
        TreeMap<String, IndexEntry> entries = index.read();
        if (entries.isEmpty()) {
            System.err.println("Nothing to commit (empty index).");
            return;
        }
        String treeHash = TreeWriter.buildTreeFromIndex(entries, store);

        // Current HEAD becomes the parent
        String parentHash = refs.resolveHead();
        List<String> parents = (parentHash != null) ? List.of(parentHash) : List.of();

        long timestamp = Instant.now().getEpochSecond();
        String identity = "BlobVault <blobvault@example.com> " + timestamp + " +0000";

        CommitObject newCommit = new CommitObject(treeHash, parents, identity, identity, message);
        byte[] commitData = CommitSerializer.serialize(newCommit);
        String newHash = store.store(ObjectType.COMMIT, commitData);

        refs.advanceHead(newHash);

        // Clean up state
        refs.deleteStateFile("CHERRY_PICK_HEAD");
        refs.deleteStateFile("ORIG_HEAD");

        System.out.println(newHash);
    }

    // -------------------------------------------------------------------------
    // --abort
    // -------------------------------------------------------------------------

    private void doAbort(Path cwd, BlobStore store, RefManager refs,
                          Index index) throws Exception {
        String origHead = refs.readStateFile("ORIG_HEAD");
        if (origHead == null) {
            System.err.println("No cherry-pick in progress (ORIG_HEAD not found).");
            return;
        }

        // Restore working tree + index to the state before the cherry-pick
        CommitObject origCommit = CommitSerializer.deserialize(store.read(origHead));
        Map<String, String> targetFiles = TreeWriter.flattenTree(origCommit.treeHash(), store);

        resetWorkingTree(cwd, targetFiles, store);

        TreeMap<String, IndexEntry> newIndex = new TreeMap<>();
        for (Map.Entry<String, String> e : targetFiles.entrySet()) {
            newIndex.put(e.getKey(), new IndexEntry("100644", e.getValue(), e.getKey()));
        }
        index.write(newIndex);

        // Restore the branch pointer — cherry-pick never detaches HEAD
        String symRef = refs.readSymbolicRef();
        refs.updateRef(symRef, origHead);

        // Clean up state
        refs.deleteStateFile("CHERRY_PICK_HEAD");
        refs.deleteStateFile("ORIG_HEAD");

        System.out.println("Cherry-pick aborted.");
    }

    // -------------------------------------------------------------------------
    // Working-tree helpers
    // -------------------------------------------------------------------------

    private void resetWorkingTree(Path cwd, Map<String, String> targetFiles,
                                   BlobStore store) throws IOException {
        // Delete tracked files that are no longer in the target
        try (var walk = Files.walk(cwd)) {
            for (Path file : walk.toList()) {
                if (!Files.isRegularFile(file)) continue;
                if (isIgnored(cwd, file)) continue;
                String rel = cwd.relativize(file).toString().replace('\\', '/');
                if (!targetFiles.containsKey(rel)) {
                    Files.deleteIfExists(file);
                }
            }
        }

        // Write / overwrite files from target
        for (Map.Entry<String, String> e : targetFiles.entrySet()) {
            Path filePath = cwd.resolve(e.getKey());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, store.read(e.getValue()));
        }
    }

    private boolean isIgnored(Path cwd, Path file) {
        Path rel = cwd.relativize(file);
        for (Path component : rel) {
            if (".blobvault".equals(component.toString())) return true;
        }
        return false;
    }
}
