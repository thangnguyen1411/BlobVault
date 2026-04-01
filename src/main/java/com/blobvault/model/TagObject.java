package com.blobvault.model;

/**
 * Represents a Git annotated tag object.
 *
 * An annotated tag wraps a commit (or any object) with metadata:
 *   - objectHash  → the hash of the tagged object (usually a commit)
 *   - objectType  → the type of the tagged object ("commit", "tree", "blob")
 *   - tagName     → the tag name (e.g., "v1.0")
 *   - tagger      → who created the tag and when
 *   - message     → tag message / release notes
 *
 * This is the fourth and final Git object type (blob, tree, commit, tag).
 * Lightweight tags don't create a tag object — they're just ref files.
 */
public record TagObject(
    String objectHash,
    String objectType,
    String tagName,
    String tagger,
    String message
) {
    public TagObject {
        if (objectHash == null || objectHash.length() != 40) {
            throw new IllegalArgumentException("objectHash must be a 40-character hex string");
        }
        if (objectType == null || objectType.isBlank()) {
            throw new IllegalArgumentException("objectType must not be null or blank");
        }
        if (tagName == null || tagName.isBlank()) {
            throw new IllegalArgumentException("tagName must not be null or blank");
        }
        if (tagger == null || tagger.isBlank()) {
            throw new IllegalArgumentException("tagger must not be null or blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be null or blank");
        }
    }
}
