package com.blobvault.storage;

import com.blobvault.model.IndexEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

/**
 * Manages the index (staging area) — the ".blobvault/index" file.
 *
 * The index is the bridge between the working directory and a commit:
 *   working dir  →  (add)   →  index  →  (commit)  →  object store
 *
 * It's stored as a human-readable text file, one entry per line:
 *   100644 802992c4220de19a90767f3000a79a31b98d0df7 hello.txt
 *   100644 6029b2a16821af99e833ddba27f8e107917669bb src/Main.java
 *
 * Fields per line: <mode> <blobHash> <relativePath>
 *   mode       — file permission bits (always 100644 for regular files)
 *   blobHash   — SHA-1 of the file content as stored in the object store
 *   path       — repo-relative path using "/" separators
 *
 * Entries are kept sorted by path so reads and diffs are deterministic.
 * The text format trades compactness for human-readability and easy debugging.
 */
public class Index {

    private final Path indexFile;

    public Index(Path repoRoot) {
        // The index lives inside .blobvault, alongside objects/ and refs/
        this.indexFile = repoRoot.resolve(".blobvault").resolve("index");
    }

    /**
     * Reads the index file into a sorted {path → IndexEntry} map.
     *
     * Returns an empty map when the index file doesn't exist yet — this is the
     * normal state for a freshly initialised repo before any "add" has been run.
     * Callers can always treat the return value as "files currently staged".
     */
    public TreeMap<String, IndexEntry> read() throws IOException {
        TreeMap<String, IndexEntry> entries = new TreeMap<>();

        // No index file → nothing has been staged yet; return empty map
        if (!Files.exists(indexFile)) {
            return entries;
        }

        // Parse each line into an IndexEntry
        for (String line : Files.readAllLines(indexFile)) {
            if (line.isBlank()) continue;

            // Expected format: "<mode> <hash> <path>"
            // Limit split to 3 so a path that contains spaces is kept intact
            String[] parts = line.split(" ", 3);
            if (parts.length != 3) continue; // skip any malformed / legacy lines

            String mode = parts[0];
            String hash = parts[1];
            String path = parts[2];
            entries.put(path, new IndexEntry(mode, hash, path));
        }

        return entries;
    }

    /**
     * Writes all entries to the index file, sorted by path.
     *
     * The entire file is replaced on every write — there is no partial update.
     * Wrapping input in a TreeMap guarantees alphabetical path order regardless
     * of what order the caller added entries, keeping the file diffable and
     * reproducible across platforms.
     */
    public void write(java.util.Map<String, IndexEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();

        // Wrap in TreeMap to ensure stable, sorted output even if a plain Map was passed in
        new TreeMap<>(entries).values().forEach(entry ->
            sb.append(entry.mode())
              .append(' ')
              .append(entry.hash())
              .append(' ')
              .append(entry.path())
              .append('\n')
        );

        // Overwrite the whole file; the index is always a complete, consistent snapshot
        Files.writeString(indexFile, sb.toString());
    }
}
