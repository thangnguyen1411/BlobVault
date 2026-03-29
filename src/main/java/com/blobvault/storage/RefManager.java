package com.blobvault.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages references (refs) — the mutable pointers that track branch tips and HEAD.
 *
 * Layout:
 *   .blobvault/HEAD                → symbolic ref (e.g., "ref: refs/heads/main") or raw hash
 *   .blobvault/refs/heads/{name}   → commit hash for each branch
 */
public class RefManager {

    private final Path blobvaultDir;

    public RefManager(Path repoRoot) {
        this.blobvaultDir = repoRoot.resolve(".blobvault");
    }

    /**
     * Resolves HEAD to a commit hash.
     * Follows symbolic refs; returns {@code null} if no commits exist on the current branch.
     */
    public String resolveHead() throws IOException {
        String headContent = Files.readString(blobvaultDir.resolve("HEAD")).trim();

        if (headContent.startsWith("ref: ")) {
            Path refFile = blobvaultDir.resolve(headContent.substring("ref: ".length()));
            if (!Files.exists(refFile)) {
                return null;
            }
            return Files.readString(refFile).trim();
        }

        return headContent;
    }

    /**
     * Returns the full ref path HEAD points to (e.g., "refs/heads/main").
     *
     * @throws IllegalStateException if HEAD is detached (contains a raw hash)
     */
    public String readSymbolicRef() throws IOException {
        String headContent = Files.readString(blobvaultDir.resolve("HEAD")).trim();

        if (!headContent.startsWith("ref: ")) {
            throw new IllegalStateException("HEAD is not a symbolic ref (detached HEAD)");
        }

        return headContent.substring("ref: ".length());
    }

    /**
     * Writes a commit hash to the given ref path (e.g., "refs/heads/main").
     * Creates parent directories if needed.
     */
    public void updateRef(String refPath, String commitHash) throws IOException {
        Path refFile = blobvaultDir.resolve(refPath);
        Files.createDirectories(refFile.getParent());
        Files.writeString(refFile, commitHash + "\n");
    }

    /**
     * Points HEAD at the given branch name by writing a symbolic ref.
     */
    public void updateHead(String branchName) throws IOException {
        Files.writeString(blobvaultDir.resolve("HEAD"), "ref: refs/heads/" + branchName + "\n");
    }

    /**
     * Returns the current branch name (e.g., "main"), or {@code null} if HEAD is detached.
     */
    public String currentBranch() throws IOException {
        String headContent = Files.readString(blobvaultDir.resolve("HEAD")).trim();
        if (headContent.startsWith("ref: refs/heads/")) {
            return headContent.substring("ref: refs/heads/".length());
        }
        return null;
    }

    /**
     * Returns all branch names sorted alphabetically, or an empty list if none exist.
     *
     * Uses Files.walk (recursive) rather than Files.list (direct children only) so
     * that slash-namespaced branches like "ntthang/ab-123/fix" are included.
     * The returned name is the path relative to refs/heads/, e.g. "ntthang/ab-123/fix".
     */
    public List<String> listBranches() throws IOException {
        Path headsDir = blobvaultDir.resolve("refs").resolve("heads");
        if (!Files.exists(headsDir)) {
            return List.of();
        }

        try (Stream<Path> entries = Files.walk(headsDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(p -> headsDir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Resolves a branch name to its commit hash.
     * Returns {@code null} if the branch does not exist.
     */
    public String resolveRef(String branchName) throws IOException {
        Path refFile = blobvaultDir.resolve("refs").resolve("heads").resolve(branchName);
        if (!Files.exists(refFile)) {
            return null;
        }
        return Files.readString(refFile).trim();
    }
}
