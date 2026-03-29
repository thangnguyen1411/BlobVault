package com.blobvault.command;

import com.blobvault.object.CommitObject;
import com.blobvault.object.CommitSerializer;
import com.blobvault.object.ObjectType;
import com.blobvault.object.TreeWriter;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.Index;
import com.blobvault.storage.IndexEntry;
import com.blobvault.storage.RefManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Creates a new commit from the staged changes in the index.
 * Equivalent to: git commit -m "message"
 *
 * What happens when you run "blobvault commit -m 'Initial commit'":
 *   1. Read the index (staged files)
 *   2. Build a tree object from the index entries
 *   3. Resolve HEAD to find the parent commit (if any)
 *   4. Create a commit object pointing to the tree and parent
 *   5. Store the commit in the object store
 *   6. Advance the current branch ref to the new commit hash
 *
 * After this, the current branch file (e.g., .blobvault/refs/heads/main)
 * contains the new commit hash, and the commit itself points back to the
 * previous one — forming the history chain.
 */
public class CommitCommand implements Command {

    @Override
    public String name() { return "commit"; }

    @Override
    public String usage() { return "commit -m <message>"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        String message = parseMessage(args);
        if (message == null) {
            System.err.println("Usage: blobvault " + usage());
            return;
        }

        BlobStore store = new BlobStore(cwd);
        RefManager refs = new RefManager(cwd);
        Index index = new Index(cwd);
        TreeMap<String, IndexEntry> entries = index.read();

        if (entries.isEmpty()) {
            System.err.println("Nothing to commit (empty index — use 'blobvault add' first)");
            return;
        }

        // Convert the flat index entries into a hierarchical tree object
        String treeHash = TreeWriter.buildTreeFromIndex(entries, store);
        if (treeHash == null) {
            System.err.println("Nothing to commit (empty index)");
            return;
        }

        // Build parent list: normally just HEAD, but after a merge also includes MERGE_HEAD
        String parentHash = refs.resolveHead();
        List<String> parents = buildParentList(cwd, parentHash);

        // Hardcoded identity — could read from a config file
        long timestamp = Instant.now().getEpochSecond();
        String identity = "BlobVault <blobvault@example.com> " + timestamp + " +0000";

        CommitObject commit = new CommitObject(treeHash, parents, identity, identity, message);
        byte[] commitData = CommitSerializer.serialize(commit);
        String commitHash = store.store(ObjectType.COMMIT, commitData);

        // Advance the branch pointer (e.g., refs/heads/main) to the new commit
        String currentRef = refs.readSymbolicRef();
        refs.updateRef(currentRef, commitHash);

        // Clean up MERGE_HEAD if this was a merge commit
        Path mergeHead = cwd.resolve(".blobvault").resolve("MERGE_HEAD");
        if (Files.exists(mergeHead)) {
            Files.delete(mergeHead);
        }

        System.out.println(commitHash);
    }

    /**
     * Builds the parent list for a new commit.
     * If MERGE_HEAD exists (after a merge with conflicts was resolved), includes both
     * HEAD and MERGE_HEAD as parents — creating a merge commit.
     */
    private List<String> buildParentList(Path cwd, String headHash) throws IOException {
        List<String> parents = new ArrayList<>();
        if (headHash != null) {
            parents.add(headHash);
        }

        Path mergeHead = cwd.resolve(".blobvault").resolve("MERGE_HEAD");
        if (Files.exists(mergeHead)) {
            String mergeParent = Files.readString(mergeHead).trim();
            if (!mergeParent.isEmpty()) {
                parents.add(mergeParent);
            }
        }

        return parents;
    }

    /**
     * Scans args for "-m" and returns the next argument as the commit message.
     * Returns {@code null} if -m is missing or has no following argument.
     */
    private String parseMessage(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("-m".equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }
}
