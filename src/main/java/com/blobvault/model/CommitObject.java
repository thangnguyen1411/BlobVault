package com.blobvault.model;

import java.util.List;

/**
 * Represents a Git commit object.
 *
 * A commit ties together:
 *   - A tree hash    → the snapshot of the project at this point in time
 *   - Parent hashes  → the previous commit(s) this builds on
 *   - Author         → who wrote the change and when
 *   - Committer      → who applied the change and when (usually same as author)
 *   - Message        → why the change was made
 *
 * Together, commits form a linked list (or DAG for merges) — the project history.
 */
public record CommitObject(
    String treeHash,
    List<String> parentHashes,   // [] = first commit, [hash] = normal, [h1, h2] = merge
    String author,
    String committer,
    String message
) {
    public CommitObject {
        // Validate tree hash
        if (treeHash == null || treeHash.length() != 40) {
            throw new IllegalArgumentException("treeHash must be a 40-character hex string");
        }

        // Validate parent hashes
        if (parentHashes == null) {
            throw new IllegalArgumentException("parentHashes must not be null (use List.of() for no parents)");
        }
        for (String parent : parentHashes) {
            if (parent == null || parent.length() != 40) {
                throw new IllegalArgumentException("Each parent hash must be a 40-character hex string");
            }
        }
        // Defensive copy — record fields should be immutable
        parentHashes = List.copyOf(parentHashes);

        // Validate metadata
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("author must not be null or blank");
        }
        if (committer == null || committer.isBlank()) {
            throw new IllegalArgumentException("committer must not be null or blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be null or blank");
        }
    }
}
