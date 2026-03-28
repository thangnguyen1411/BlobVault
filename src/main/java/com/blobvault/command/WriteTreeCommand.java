package com.blobvault.command;

import com.blobvault.object.ObjectType;
import com.blobvault.object.TreeEntry;
import com.blobvault.object.TreeSerializer;
import com.blobvault.storage.BlobStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Recursively snapshots the working directory into tree and blob objects.
 * Equivalent to: git write-tree
 *
 * Example — given this directory:
 *   myrepo/
 *     hello.txt
 *     src/
 *       Main.java
 *
 * Produces:
 *   blob (hello.txt content)   → stored, hash = H1
 *   blob (Main.java content)   → stored, hash = H2
 *   tree (src/)                → stored, contains: "Main.java → H2"
 *   tree (myrepo/)             → stored, contains: "hello.txt → H1", "src → src-tree-hash"
 *
 * Prints the root tree hash to stdout.
 */
public class WriteTreeCommand implements Command {

    /** Never snapshot our own storage directory */
    private static final Set<String> IGNORED = Set.of(".blobvault");

    @Override
    public String name() { return "write-tree"; }

    @Override
    public String usage() { return "write-tree"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        BlobStore store = new BlobStore(cwd);
        String rootHash = writeTree(cwd, store);
        System.out.println(rootHash);
    }

    /**
     * Recursively builds a tree object for the given directory.
     * Returns null if the directory is empty — Git does not track empty directories.
     */
    private String writeTree(Path directory, BlobStore store) throws IOException {
        List<TreeEntry> entries = new ArrayList<>();

        // sorted() ensures the same directory always produces the same hash
        try (Stream<Path> children = Files.list(directory).sorted()) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();

                if (IGNORED.contains(name)) continue;

                if (Files.isRegularFile(child)) {
                    byte[] content = Files.readAllBytes(child);
                    String blobHash = store.store(ObjectType.BLOB, content);
                    entries.add(new TreeEntry(TreeEntry.MODE_FILE, name, blobHash));

                } else if (Files.isDirectory(child)) {
                    String subtreeHash = writeTree(child, store);
                    if (subtreeHash != null) {
                        entries.add(new TreeEntry(TreeEntry.MODE_TREE, name, subtreeHash));
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            return null;
        }

        byte[] treeData = TreeSerializer.serialize(entries);
        return store.store(ObjectType.TREE, treeData);
    }
}
