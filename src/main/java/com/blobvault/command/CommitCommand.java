package com.blobvault.command;

import com.blobvault.object.CommitObject;
import com.blobvault.object.CommitSerializer;
import com.blobvault.object.ObjectType;
import com.blobvault.object.TreeWriter;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.RefManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Creates a new commit from the current working directory state.
 * Equivalent to: git commit -m "message"
 *
 * What happens when you run "blobvault commit -m 'Initial commit'":
 *
 *   1. Snapshot the working directory → tree hash   (TreeWriter)
 *   2. Read HEAD → find the parent commit           (RefManager)
 *   3. Build the commit object                      (CommitObject + CommitSerializer)
 *   4. Store it in the object store                  (BlobStore)
 *   5. Update the branch to point to this commit     (RefManager)
 *
 * After this, .blobvault/refs/heads/main contains the new commit hash,
 * and the commit itself points back to the previous one — forming the history chain.
 */
public class CommitCommand implements Command {

    @Override
    public String name() { return "commit"; }

    @Override
    public String usage() { return "commit -m <message>"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        // --- Parse the -m flag ---
        String message = parseMessage(args);
        if (message == null) {
            System.err.println("Usage: blobvault " + usage());
            return;
        }

        BlobStore store = new BlobStore(cwd);
        RefManager refs = new RefManager(cwd);

        // --- Step 1: Snapshot the working directory into a tree ---
        String treeHash = TreeWriter.writeTree(cwd, store);
        if (treeHash == null) {
            System.err.println("Nothing to commit (empty working directory)");
            return;
        }

        // --- Step 2: Find the parent commit (if any) ---
        String parentHash = refs.resolveHead();
        List<String> parents = (parentHash != null) ? List.of(parentHash) : List.of();

        // --- Step 3: Build author/committer lines ---
        // Format: "Name <email> <unix-timestamp> <timezone>"
        // Hardcoded for now — Phase 8 could read from a config file
        long timestamp = Instant.now().getEpochSecond();
        String identity = "BlobVault <blobvault@example.com> " + timestamp + " +0000";

        // --- Step 4: Create, serialize, and store the commit ---
        CommitObject commit = new CommitObject(treeHash, parents, identity, identity, message);
        byte[] commitData = CommitSerializer.serialize(commit);
        String commitHash = store.store(ObjectType.COMMIT, commitData);

        // --- Step 5: Advance the branch pointer ---
        // e.g., write the new commit hash to .blobvault/refs/heads/main
        String currentRef = refs.readSymbolicRef();
        refs.updateRef(currentRef, commitHash);

        System.out.println(commitHash);
    }

    /**
     * Scans args for "-m" and returns the next argument as the message.
     * Returns null if -m is missing or has no following argument.
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
