package com.blobvault.object;

/**
 * One entry in a tree object.
 *
 * Each entry represents either a file (blob) or a subdirectory (tree):
 *   MODE_FILE "100644" → regular file (blob)
 *   MODE_TREE "40000"  → subdirectory (tree)
 *
 * Entries are sorted by name, matching Git's behavior.
 */
public record TreeEntry(String mode, String name, String hash) implements Comparable<TreeEntry> {

    public static final String MODE_FILE = "100644";
    public static final String MODE_TREE = "40000";

    public TreeEntry {
        // Compact constructor — validates on creation
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("mode must not be null or blank");
        }
        if (name == null || name.contains("/") || name.contains("\0")) {
            throw new IllegalArgumentException("name must not be null or contain '/' or null bytes");
        }
        if (hash == null || hash.length() != 40) {
            throw new IllegalArgumentException("hash must be a 40-character hex string");
        }
    }

    /** Is this entry a subdirectory (tree)? */
    public boolean isTree() {
        return MODE_TREE.equals(mode);
    }

    /** Returns the object type of this entry. */
    public ObjectType type() {
        return isTree() ? ObjectType.TREE : ObjectType.BLOB;
    }

    /**
     * Returns the mode zero-padded to 6 characters for display.
     * Git stores "40000" in binary but displays "040000" in ls-tree.
     */
    public String displayMode() {
        return String.format("%06d", Integer.parseInt(mode));
    }

    @Override
    public int compareTo(TreeEntry other) {
        return this.name.compareTo(other.name);
    }
}
