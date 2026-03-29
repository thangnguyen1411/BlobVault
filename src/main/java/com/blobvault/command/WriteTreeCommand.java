package com.blobvault.command;

import com.blobvault.object.TreeWriter;
import com.blobvault.storage.BlobStore;

import java.nio.file.Path;

/**
 * Recursively snapshots the working directory into tree and blob objects.
 * Equivalent to: git write-tree
 *
 * Delegates to TreeWriter for the actual recursive logic.
 * Prints the root tree hash to stdout.
 */
public class WriteTreeCommand implements Command {

    @Override
    public String name() { return "write-tree"; }

    @Override
    public String usage() { return "write-tree"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        BlobStore store = new BlobStore(cwd);
        String rootHash = TreeWriter.writeTree(cwd, store);
        System.out.println(rootHash);
    }
}
