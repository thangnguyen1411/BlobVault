package com.blobvault.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages references (refs) — the mutable pointers in BlobVault.
 *
 * While the object store is immutable (content-addressed, append-only),
 * refs are the opposite: they're mutable files that point to commit hashes.
 *
 * Key concepts:
 *   HEAD                     → symbolic ref, points to a branch (e.g., "ref: refs/heads/main")
 *   refs/heads/main          → contains the commit hash of the latest commit on "main"
 *   refs/heads/feature       → contains the commit hash of the latest commit on "feature"
 *
 * When you make a new commit, we just overwrite the branch file with the new hash.
 * That's all a branch is — a one-line text file.
 */
public class RefManager {

    private final Path blobvaultDir;

    public RefManager(Path repoRoot) {
        this.blobvaultDir = repoRoot.resolve(".blobvault");
    }

    /**
     * Resolves HEAD to the current commit hash.
     *
     * Follows the chain: HEAD → "ref: refs/heads/main" → read that file → commit hash.
     * Returns null if no commits have been made yet (branch file doesn't exist).
     */
    public String resolveHead() throws IOException {
        String headContent = Files.readString(blobvaultDir.resolve("HEAD")).trim();

        // HEAD is a symbolic ref like "ref: refs/heads/main"
        if (headContent.startsWith("ref: ")) {
            String refPath = headContent.substring("ref: ".length());
            Path refFile = blobvaultDir.resolve(refPath);

            // Branch file doesn't exist yet = no commits on this branch
            if (!Files.exists(refFile)) {
                return null;
            }
            return Files.readString(refFile).trim();
        }

        // Detached HEAD — raw commit hash (future-proofing for Phase 5)
        return headContent;
    }

    /**
     * Returns the ref path that HEAD points to (e.g., "refs/heads/main").
     * This tells us which branch we're on.
     */
    public String readSymbolicRef() throws IOException {
        String headContent = Files.readString(blobvaultDir.resolve("HEAD")).trim();

        if (!headContent.startsWith("ref: ")) {
            throw new IllegalStateException("HEAD is not a symbolic ref (detached HEAD)");
        }

        return headContent.substring("ref: ".length());
    }

    /**
     * Updates a ref to point to a new commit hash.
     * This is what "advances" a branch when you make a new commit.
     *
     * Example: updateRef("refs/heads/main", "a1b2c3d4...") writes
     *          the hash to .blobvault/refs/heads/main
     */
    public void updateRef(String refPath, String commitHash) throws IOException {
        Path refFile = blobvaultDir.resolve(refPath);
        Files.createDirectories(refFile.getParent());
        Files.writeString(refFile, commitHash + "\n");
    }
}
