package com.blobvault.object;

import com.blobvault.storage.BlobStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Recursively builds tree objects from a working directory.
 *
 * Extracted from WriteTreeCommand so that CommitCommand can reuse
 * the same logic without going through CLI dispatch.
 */
public class TreeWriter {

    /** Directories to skip — we never snapshot our own storage */
    private static final Set<String> IGNORED = Set.of(".blobvault");

    /**
     * Recursively builds a tree object for the given directory.
     *
     * For each file:      hash as blob → TreeEntry("100644", name, blobHash)
     * For each directory:  recurse     → TreeEntry("40000",  name, subtreeHash)
     *
     * Returns null if the directory is empty — Git does not track empty directories.
     */
    public static String writeTree(Path directory, BlobStore store) throws IOException {
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

        // Empty directory → no tree object (Git doesn't track empty dirs)
        if (entries.isEmpty()) {
            return null;
        }

        // Serialize entries to Git binary format and store as tree object
        byte[] treeData = TreeSerializer.serialize(entries);
        return store.store(ObjectType.TREE, treeData);
    }
}
