package com.blobvault.command;

import com.blobvault.model.CommitObject;
import com.blobvault.model.IndexEntry;
import com.blobvault.model.ObjectType;
import com.blobvault.service.CherryPicker;
import com.blobvault.service.CommitResolver;
import com.blobvault.service.CommitSerializer;
import com.blobvault.service.CommitWalker;
import com.blobvault.service.MergeBase;
import com.blobvault.service.TreeWriter;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.Index;
import com.blobvault.storage.RefManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Replays commits from the current branch on top of another commit.
 * Equivalent to: git rebase {@literal <target>}
 *
 * Modes:
 *   rebase {@literal <target>}   — rebase current branch onto target
 *   rebase --continue            — resume after resolving conflicts
 *   rebase --abort               — abandon and restore original state
 */
public class RebaseCommand implements Command {

    @Override
    public String name() { return "rebase"; }

    @Override
    public String usage() { return "rebase <target> | --continue | --abort"; }

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
            default           -> doRebase(cwd, args[1], store, refs, index);
        }
    }

    // -------------------------------------------------------------------------
    // Normal rebase
    // -------------------------------------------------------------------------

    private void doRebase(Path cwd, String targetExpr, BlobStore store,
                           RefManager refs, Index index) throws Exception {
        CommitResolver resolver = new CommitResolver(refs, store);
        String targetHash = resolver.resolve(targetExpr);
        if (targetHash == null) {
            System.err.println("Cannot resolve '" + targetExpr + "' to a commit.");
            return;
        }

        String headHash = refs.resolveHead();
        if (headHash == null) {
            System.err.println("Cannot rebase: no commits on current branch.");
            return;
        }

        String currentBranch = refs.currentBranch();
        if (currentBranch == null) {
            System.err.println("Cannot rebase: HEAD is detached. Check out a branch first.");
            return;
        }

        String mergeBase = MergeBase.find(headHash, targetHash, store);

        if (headHash.equals(mergeBase)) {
            // HEAD is an ancestor of target → fast-forward style
            System.out.println("Current branch is already up to date.");
            // Actually means we should fast-forward
            fastForwardBranch(cwd, currentBranch, targetHash, store, refs, index);
            return;
        }

        if (targetHash.equals(mergeBase)) {
            // target is an ancestor of HEAD → already ahead
            System.out.println("Already up to date.");
            return;
        }

        // Collect commits to replay (oldest first)
        List<String> toReplay = new CommitWalker(store).collectRange(mergeBase, headHash);
        if (toReplay.isEmpty()) {
            System.out.println("Nothing to rebase.");
            return;
        }

        // Save rebase state
        refs.writeStateFile("rebase-state/orig-head", headHash);
        refs.writeStateFile("rebase-state/head-name", currentBranch);
        refs.writeStateFile("rebase-state/onto", targetHash);
        refs.writeStateFile("rebase-state/todo", String.join("\n", toReplay));
        refs.writeStateFile("rebase-state/done", "");

        // Reset working tree + index to target commit
        resetToCommit(cwd, targetHash, store, refs, index);

        // Detach HEAD to target
        refs.detachHead(targetHash);

        // Start replaying
        continueRebase(cwd, store, refs, index);
    }

    // -------------------------------------------------------------------------
    // --continue
    // -------------------------------------------------------------------------

    private void doContinue(Path cwd, BlobStore store, RefManager refs,
                              Index index) throws Exception {
        String todoContent = refs.readStateFile("rebase-state/todo");
        if (todoContent == null) {
            System.err.println("No rebase in progress.");
            return;
        }

        String currentHash = refs.readStateFile("rebase-state/current");
        if (currentHash == null) {
            System.err.println("No conflicted commit to continue from.");
            return;
        }

        // Build a new commit from the resolved index, using the original message
        CommitObject originalCommit = CommitSerializer.deserialize(store.read(currentHash));
        String message = originalCommit.message();

        TreeMap<String, IndexEntry> entries = index.read();
        if (entries.isEmpty()) {
            System.err.println("Nothing to commit (empty index).");
            return;
        }
        String treeHash = TreeWriter.buildTreeFromIndex(entries, store);

        String parentHash = refs.resolveHead();
        List<String> parents = (parentHash != null) ? List.of(parentHash) : List.of();

        long timestamp = Instant.now().getEpochSecond();
        String identity = "BlobVault <blobvault@example.com> " + timestamp + " +0000";

        CommitObject newCommit = new CommitObject(treeHash, parents, identity, identity, message);
        byte[] commitData = CommitSerializer.serialize(newCommit);
        String newHash = store.store(ObjectType.COMMIT, commitData);

        refs.advanceHead(newHash);

        // Move current → done, clear current
        String done = refs.readStateFile("rebase-state/done");
        done = (done == null || done.isBlank()) ? currentHash : done + "\n" + currentHash;
        refs.writeStateFile("rebase-state/done", done);
        refs.deleteStateFile("rebase-state/current");

        // Resume the replay loop
        continueRebase(cwd, store, refs, index);
    }

    // -------------------------------------------------------------------------
    // --abort
    // -------------------------------------------------------------------------

    private void doAbort(Path cwd, BlobStore store, RefManager refs,
                          Index index) throws Exception {
        String origHead = refs.readStateFile("rebase-state/orig-head");
        String headName = refs.readStateFile("rebase-state/head-name");

        if (origHead == null || headName == null) {
            System.err.println("No rebase in progress.");
            return;
        }

        // Restore working tree + index to original HEAD
        resetToCommit(cwd, origHead, store, refs, index);

        // Re-attach HEAD to the original branch
        refs.updateHead(headName);
        refs.updateRef("refs/heads/" + headName, origHead);

        // Clean up all rebase state
        cleanupRebaseState(refs);

        System.out.println("Rebase aborted.");
    }

    // -------------------------------------------------------------------------
    // Core replay loop
    // -------------------------------------------------------------------------

    private void continueRebase(Path cwd, BlobStore store, RefManager refs,
                                  Index index) throws Exception {
        String todoContent = refs.readStateFile("rebase-state/todo");
        List<String> todo = parseTodo(todoContent);

        String doneContent = refs.readStateFile("rebase-state/done");
        List<String> done = parseTodo(doneContent); // reuse same parser

        while (!todo.isEmpty()) {
            String hash = todo.removeFirst();
            refs.writeStateFile("rebase-state/todo", String.join("\n", todo));

            CherryPicker picker = new CherryPicker(cwd, store, refs, index);
            String newHash = picker.cherryPick(hash, false);

            if (newHash != null) {
                // Success — record in done
                done.add(hash);
                refs.writeStateFile("rebase-state/done", String.join("\n", done));
            } else {
                // Conflict — save which commit we were on and stop
                refs.writeStateFile("rebase-state/current", hash);

                System.out.println("CONFLICT: Rebase conflict applying " + hash.substring(0, 7));
                System.out.println("Resolve the conflicts and then run:");
                System.out.println("  blobvault rebase --continue");
                System.out.println("Or to abort:");
                System.out.println("  blobvault rebase --abort");
                return;
            }
        }

        // All commits replayed — finalize
        finalizeRebase(refs);
    }

    private void finalizeRebase(RefManager refs) throws IOException {
        String headName = refs.readStateFile("rebase-state/head-name");
        String currentHash = refs.resolveHead();

        // Update the branch pointer to the final rebased commit
        refs.updateRef("refs/heads/" + headName, currentHash);

        // Re-attach HEAD to the branch
        refs.updateHead(headName);

        // Clean up all rebase state
        cleanupRebaseState(refs);

        System.out.println("Successfully rebased and updated refs/heads/" + headName + ".");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses a newline-separated list of commit hashes, skipping blank lines.
     * Returns a mutable list.
     */
    private List<String> parseTodo(String content) {
        List<String> result = new ArrayList<>();
        if (content == null || content.isBlank()) return result;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Resets the working tree and index to match the given commit.
     */
    private void resetToCommit(Path cwd, String commitHash, BlobStore store,
                                 RefManager refs, Index index) throws IOException {
        CommitObject commit = CommitSerializer.deserialize(store.read(commitHash));
        Map<String, String> targetFiles = TreeWriter.flattenTree(commit.treeHash(), store);

        // Delete tracked files not in target
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

        // Write files from target
        for (Map.Entry<String, String> e : targetFiles.entrySet()) {
            Path filePath = cwd.resolve(e.getKey());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, store.read(e.getValue()));
        }

        // Rebuild index
        TreeMap<String, IndexEntry> newIndex = new TreeMap<>();
        for (Map.Entry<String, String> e : targetFiles.entrySet()) {
            newIndex.put(e.getKey(), new IndexEntry("100644", e.getValue(), e.getKey()));
        }
        index.write(newIndex);
    }

    private void fastForwardBranch(Path cwd, String branchName, String targetHash,
                                    BlobStore store, RefManager refs, Index index) throws IOException {
        CommitObject commit = CommitSerializer.deserialize(store.read(targetHash));
        Map<String, String> targetFiles = TreeWriter.flattenTree(commit.treeHash(), store);

        for (Map.Entry<String, String> e : targetFiles.entrySet()) {
            Path filePath = cwd.resolve(e.getKey());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, store.read(e.getValue()));
        }

        TreeMap<String, IndexEntry> newIndex = new TreeMap<>();
        for (Map.Entry<String, String> e : targetFiles.entrySet()) {
            newIndex.put(e.getKey(), new IndexEntry("100644", e.getValue(), e.getKey()));
        }
        index.write(newIndex);

        refs.updateRef("refs/heads/" + branchName, targetHash);
        System.out.println("Fast-forwarded " + branchName + " to " + targetHash.substring(0, 7) + ".");
    }

    private void cleanupRebaseState(RefManager refs) throws IOException {
        refs.deleteStateFile("rebase-state/orig-head");
        refs.deleteStateFile("rebase-state/head-name");
        refs.deleteStateFile("rebase-state/onto");
        refs.deleteStateFile("rebase-state/todo");
        refs.deleteStateFile("rebase-state/done");
        refs.deleteStateFile("rebase-state/current");
    }

    private boolean isIgnored(Path cwd, Path file) {
        Path rel = cwd.relativize(file);
        for (Path component : rel) {
            if (".blobvault".equals(component.toString())) return true;
        }
        return false;
    }
}
