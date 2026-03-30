package com.blobvault.model;

/**
 * One entry in the index (staging area).
 *
 * Each entry represents a single file that has been staged for the next commit.
 * The three fields mirror one line in the ".blobvault/index" text file:
 *
 *   mode  "100644"  → Unix permission bits; 100644 means a regular, non-executable file
 *   hash            → SHA-1 of the blob object already written to the object store
 *   path            → repo-relative path using "/" separators, e.g. "src/Main.java"
 *
 * Design notes:
 *   - Using a Java record keeps this immutable and gives equals/hashCode for free.
 *   - Implementing Comparable lets Collections.sort() and TreeSet sort by path
 *     without needing an explicit Comparator at call sites.
 *   - The compact constructor (validation block) runs on every construction,
 *     so no invalid IndexEntry can ever exist at runtime.
 */
public record IndexEntry(String mode, String hash, String path) implements Comparable<IndexEntry> {

    /**
     * Compact constructor — validates every field at construction time.
     *
     * Enforcing these invariants here means the rest of the codebase can
     * trust that any IndexEntry it receives is already well-formed.
     */
    public IndexEntry {
        // mode must be present (e.g. "100644"); an empty mode is a sign of a parse bug
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("mode must not be null or blank");
        }

        // SHA-1 hashes are always exactly 40 hex characters; wrong length = corrupt data
        if (hash == null || hash.length() != 40) {
            throw new IllegalArgumentException("hash must be a 40-character hex string");
        }

        // path must be non-empty and repo-relative (never an absolute filesystem path)
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be null or blank");
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("path must be relative, not absolute");
        }
    }

    /**
     * Natural ordering by path, so sorted collections (TreeMap, TreeSet) produce
     * deterministic, alphabetical output without an explicit Comparator.
     */
    @Override
    public int compareTo(IndexEntry other) {
        return this.path.compareTo(other.path);
    }
}
