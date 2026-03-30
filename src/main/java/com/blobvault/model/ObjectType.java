package com.blobvault.model;

/**
 * The type of a Git object.
 * Used both as the header label ("blob 5\0...") and for display in ls-tree.
 */
public enum ObjectType {
    BLOB("blob"),
    TREE("tree"),
    COMMIT("commit");

    private final String label;

    ObjectType(String label) {
        this.label = label;
    }

    /** The string written into the object header and displayed in ls-tree. */
    public String label() {
        return label;
    }
}
