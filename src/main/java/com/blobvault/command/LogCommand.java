package com.blobvault.command;

import com.blobvault.object.CommitObject;
import com.blobvault.object.CommitSerializer;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.RefManager;

import java.nio.file.Path;

/**
 * Walks the commit history and prints each commit.
 * Equivalent to: git log
 *
 * How it works:
 *   1. Resolve HEAD → latest commit hash
 *   2. Read commit → print it
 *   3. Follow the first parent pointer → repeat
 *   4. Stop when we reach a commit with no parents (the initial commit)
 *
 * This is a simple linked-list traversal. For merge commits (Phase 7),
 * we only follow the first parent — matching "git log --first-parent".
 */
public class LogCommand implements Command {

    @Override
    public String name() { return "log"; }

    @Override
    public String usage() { return "log"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        BlobStore store = new BlobStore(cwd);
        RefManager refs = new RefManager(cwd);

        // Start at HEAD
        String currentHash = refs.resolveHead();

        if (currentHash == null) {
            System.out.println("No commits yet.");
            return;
        }

        // Walk the parent chain until we run out of parents
        while (currentHash != null) {
            // Read and deserialize the commit object
            byte[] data = store.read(currentHash);
            CommitObject commit = CommitSerializer.deserialize(data);

            // Print in Git's log format
            System.out.println("commit " + currentHash);
            System.out.println("Author: " + commit.author());
            System.out.println();
            System.out.println("    " + commit.message());
            System.out.println();

            // Follow first parent (or stop if no parents)
            currentHash = commit.parentHashes().isEmpty()
                ? null
                : commit.parentHashes().get(0);
        }
    }
}
