package com.blobvault.command;

import com.blobvault.model.ObjectType;
import com.blobvault.storage.BlobStore;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a file, stores it as a blob in the object store, and prints its hash.
 * Equivalent to: git hash-object -w <file>
 */
public class HashObjectCommand implements Command {

    @Override
    public String name() { return "hash-object"; }

    @Override
    public String usage() { return "hash-object <file>"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: blobvault " + usage());
            return;
        }

        Path file = Path.of(args[1]);
        if (!Files.exists(file)) {
            System.err.println("File not found: " + args[1]);
            return;
        }

        byte[] content = Files.readAllBytes(file);
        BlobStore store = new BlobStore(cwd);
        String hash = store.store(ObjectType.BLOB, content);

        System.out.println(hash);
    }
}
