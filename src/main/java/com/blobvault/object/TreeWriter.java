package com.blobvault.object;

import com.blobvault.storage.BlobStore;
import com.blobvault.storage.IndexEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    /**
     * Builds a hierarchical tree from a flat index.
     *
     * The index has flat paths like "src/Main.java", but Git trees are hierarchical:
     *   root tree → blob "hello.txt", tree "src/"
     *   src tree  → blob "Main.java"
     *
     * Algorithm:
     *   1. Separate direct files (no "/") from subdirectory entries (has "/")
     *   2. Group subdirectory entries by their first path component
     *   3. For each group, strip the leading directory and recurse
     *   4. Serialize this level's tree entries and store
     *
     * Returns null if entries is empty.
     */
    public static String buildTreeFromIndex(Map<String, IndexEntry> entries, BlobStore store)
            throws IOException {
        if (entries.isEmpty()) {
            return null;
        }

        List<TreeEntry> treeEntries = new ArrayList<>();

        // Separate: direct files vs subdirectory entries
        // e.g., "hello.txt" is direct, "src/Main.java" belongs to "src" subdirectory
        Map<String, Map<String, IndexEntry>> subdirs = new TreeMap<>();

        for (IndexEntry entry : entries.values()) {
            int slashIndex = entry.path().indexOf('/');

            if (slashIndex == -1) {
                // Direct file at this level — e.g., "hello.txt"
                treeEntries.add(new TreeEntry(entry.mode(), entry.path(), entry.hash()));
            } else {
                // Subdirectory entry — e.g., "src/Main.java" → dir="src", rest="Main.java"
                String dirName = entry.path().substring(0, slashIndex);
                String restPath = entry.path().substring(slashIndex + 1);

                subdirs.computeIfAbsent(dirName, k -> new TreeMap<>())
                       .put(restPath, new IndexEntry(entry.mode(), entry.hash(), restPath));
            }
        }

        // Recurse into each subdirectory to build sub-trees
        for (Map.Entry<String, Map<String, IndexEntry>> subdir : subdirs.entrySet()) {
            String subtreeHash = buildTreeFromIndex(subdir.getValue(), store);
            if (subtreeHash != null) {
                treeEntries.add(new TreeEntry(TreeEntry.MODE_TREE, subdir.getKey(), subtreeHash));
            }
        }

        byte[] treeData = TreeSerializer.serialize(treeEntries);
        return store.store(ObjectType.TREE, treeData);
    }

    /**
     * Flattens a tree into a map of {relative-path → blob-hash}.
     *
     * This is the reverse of buildTreeFromIndex — given a root tree hash,
     * recursively walk all sub-trees and collect every blob with its full path.
     *
     * Used by StatusCommand to compare the HEAD commit's tree against the index.
     *
     * Example: a tree containing "hello.txt" and subtree "src/" with "Main.java"
     *          returns: {"hello.txt" → hash1, "src/Main.java" → hash2}
     */
    public static Map<String, String> flattenTree(String treeHash, BlobStore store)
            throws IOException {
        Map<String, String> result = new TreeMap<>();
        flattenTreeRecursive(treeHash, "", store, result);
        return result;
    }

    private static void flattenTreeRecursive(String treeHash, String prefix,
                                              BlobStore store, Map<String, String> result)
            throws IOException {
        byte[] treeData = store.read(treeHash);
        List<TreeEntry> entries = TreeSerializer.deserialize(treeData);

        for (TreeEntry entry : entries) {
            String fullPath = prefix.isEmpty() ? entry.name() : prefix + "/" + entry.name();

            if (entry.isTree()) {
                // Recurse into subtree
                flattenTreeRecursive(entry.hash(), fullPath, store, result);
            } else {
                // Blob — add to result
                result.put(fullPath, entry.hash());
            }
        }
    }
}
