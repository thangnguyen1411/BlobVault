package com.blobvault.command;

import com.blobvault.BlobStore;

import java.nio.file.Path;

/**
 * Retrieves an object by its hash and prints its content.
 * Equivalent to: git cat-file -p <hash>
 */
public class CatFileCommand implements Command {

    @Override
    public String name() { return "cat-file"; }

    @Override
    public String usage() { return "cat-file <hash>"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: blobvault " + usage());
            return;
        }

        BlobStore store = new BlobStore(cwd);
        byte[] content = store.read(args[1]);
        System.out.write(content);
        System.out.flush();
    }
}
