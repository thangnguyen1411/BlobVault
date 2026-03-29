package com.blobvault.command;

import com.blobvault.object.ObjectType;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.Index;
import com.blobvault.storage.IndexEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Stages files for the next commit by adding them to the index.
 * Equivalent to: git add {@literal <file-or-directory>}
 *
 * What happens when you run "blobvault add hello.txt":
 *   1. Read the file content
 *   2. Store it as a blob in the object store (hash + compress)
 *   3. Add an entry to .blobvault/index: "100644 {@literal <hash>} hello.txt"
 *
 * The index acts as a snapshot of what WILL go into the next commit.
 * Running "add" again on a modified file simply overwrites its entry —
 * the index always reflects the most recently staged version.
 *
 * Supports:
 *   blobvault add hello.txt        — stage one file
 *   blobvault add src/             — stage all files in a directory
 *   blobvault add .                — stage everything in the working directory
 */
public class AddCommand implements Command {

    private static final Set<String> IGNORED = Set.of(".blobvault");

    @Override
    public String name() { return "add"; }

    @Override
    public String usage() { return "add <file-or-directory>"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: blobvault " + usage());
            return;
        }

        BlobStore store = new BlobStore(cwd);
        Index index = new Index(cwd);
        TreeMap<String, IndexEntry> entries = index.read();

        Path target = cwd.resolve(args[1]).normalize();

        if (!Files.exists(target)) {
            System.err.println("Path not found: " + args[1]);
            return;
        }

        if (Files.isRegularFile(target)) {
            addFile(cwd, target, store, entries);
        } else if (Files.isDirectory(target)) {
            addDirectory(cwd, target, store, entries);
        }

        index.write(entries);
    }

    private void addFile(Path cwd, Path file, BlobStore store,
                         TreeMap<String, IndexEntry> entries) throws IOException {
        byte[] content = Files.readAllBytes(file);
        String blobHash = store.store(ObjectType.BLOB, content);
        String relativePath = cwd.relativize(file).toString().replace('\\', '/');
        entries.put(relativePath, new IndexEntry("100644", blobHash, relativePath));
    }

    private void addDirectory(Path cwd, Path directory, BlobStore store,
                              TreeMap<String, IndexEntry> entries) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path file : walk.toList()) {
                if (!Files.isRegularFile(file)) continue;
                if (isIgnored(cwd, file)) continue;
                addFile(cwd, file, store, entries);
            }
        }
    }

    private boolean isIgnored(Path cwd, Path file) {
        Path relative = cwd.relativize(file);
        for (Path component : relative) {
            if (IGNORED.contains(component.toString())) {
                return true;
            }
        }
        return false;
    }
}
