package com.blobvault.command;

import com.blobvault.model.CommitObject;
import com.blobvault.service.CommitSerializer;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.RefManager;

import java.nio.file.Path;

/**
 * Walks the commit history from HEAD and prints each commit.
 * Follows first parent only (equivalent to {@code git log --first-parent}).
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

        String currentHash = refs.resolveHead();
        if (currentHash == null) {
            System.out.println("No commits yet.");
            return;
        }

        while (currentHash != null) {
            byte[] data = store.read(currentHash);
            CommitObject commit = CommitSerializer.deserialize(data);

            System.out.println("commit " + currentHash);
            System.out.println("Author: " + commit.author());
            System.out.println();
            System.out.println("    " + commit.message());
            System.out.println();

            currentHash = commit.parentHashes().isEmpty()
                ? null
                : commit.parentHashes().getFirst();
        }
    }
}
