package com.blobvault.command;

import com.blobvault.object.CommitObject;
import com.blobvault.object.CommitSerializer;
import com.blobvault.object.ObjectType;
import com.blobvault.object.TreeWriter;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.Index;
import com.blobvault.storage.IndexEntry;
import com.blobvault.storage.RefManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Shows the state of the working directory and staging area.
 * Equivalent to: git status
 *
 * Compares three states:
 *   1. HEAD tree   — the snapshot recorded by the last commit
 *   2. Index       — the staging area; what "add" has queued for the next commit
 *   3. Working dir — the actual files on disk right now
 *
 * Each comparison answers a different question:
 *   Index vs HEAD        → "what will change in the next commit?"
 *   Working dir vs Index → "what have I edited but not yet staged?"
 *   Working dir \ Index  → "what files does blobvault not know about at all?"
 *
 * Reports:
 *   - "Changes to be committed"        (index vs HEAD)
 *   - "Changes not staged for commit"  (working dir vs index)
 *   - "Untracked files"                (in working dir but not in index)
 */
public class StatusCommand implements Command {

    /** Directories whose contents should never appear in status output. */
    private static final Set<String> IGNORED = Set.of(".blobvault");

    @Override
    public String name() { return "status"; }

    @Override
    public String usage() { return "status"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        BlobStore store = new BlobStore(cwd);
        RefManager refs = new RefManager(cwd);
        Index index = new Index(cwd);

        // --- Step 1: Load all three states as {path → blobHash} maps ---
        // Normalising everything to the same map shape makes the comparisons uniform.

        // HEAD tree: flattened from the last commit's tree object.
        // Empty when there are no commits yet (fresh repo).
        Map<String, String> headFiles = loadHeadFiles(store, refs);

        // Index: what the user has staged with "add".
        // We only need path→hash for comparison; discard the mode field here.
        TreeMap<String, IndexEntry> indexEntries = index.read();
        Map<String, String> indexFiles = new TreeMap<>();
        for (IndexEntry entry : indexEntries.values()) {
            indexFiles.put(entry.path(), entry.hash());
        }

        // Working directory: every regular file on disk, hashed on-the-fly.
        // BlobStore.hash() is used so no objects are written as a side effect.
        Map<String, String> workingFiles = scanWorkingDirectory(cwd, store);

        // --- Step 2: Run the three comparisons and print each section ---
        // Each helper returns true if it printed anything, so we can detect
        // the "nothing to report" case and print a clean-tree message instead.

        boolean anyOutput = false;

        // What will go into the next commit (index vs HEAD)
        anyOutput |= reportStagedChanges(indexFiles, headFiles);

        // Edits made since the last "add" (working dir vs index)
        anyOutput |= reportUnstagedChanges(workingFiles, indexFiles);

        // Files blobvault has never seen (working dir \ index)
        anyOutput |= reportUntrackedFiles(workingFiles, indexFiles);

        if (!anyOutput) {
            System.out.println("nothing to commit, working tree clean");
        }
    }

    /**
     * Loads a flat {path → blobHash} map from the tree recorded in HEAD.
     *
     * Returns an empty map when HEAD doesn't exist yet (no commits).
     * That means every staged file will appear as "new file" on first commit.
     */
    private Map<String, String> loadHeadFiles(BlobStore store, RefManager refs)
            throws IOException {
        // No HEAD → fresh repo; treat the committed tree as completely empty
        String headCommit = refs.resolveHead();
        if (headCommit == null) {
            return new TreeMap<>();
        }

        // Deserialise the commit to get its tree hash, then flatten the tree
        // into a simple path→hash map for easy diff-style comparison
        byte[] commitData = store.read(headCommit);
        CommitObject commit = CommitSerializer.deserialize(commitData);
        return TreeWriter.flattenTree(commit.treeHash(), store);
    }

    /**
     * Scans the working directory and returns a {path → blobHash} map.
     *
     * Hashes are computed with BlobStore.hash() (no objects written to disk).
     * This lets us detect whether a file has changed since it was last staged
     * by comparing its hash directly against the index entry.
     */
    private Map<String, String> scanWorkingDirectory(Path cwd, BlobStore store)
            throws IOException {
        Map<String, String> result = new TreeMap<>();

        try (Stream<Path> walk = Files.walk(cwd)) {
            for (Path file : walk.toList()) {
                // Only hash regular files; skip directories and symlinks
                if (!Files.isRegularFile(file)) continue;

                // Skip .blobvault/... — internal metadata is never user-facing
                if (isIgnored(cwd, file)) continue;

                // Hash the file content using the same blob-wrapping logic as "add"
                // so comparisons against stored blob hashes are apples-to-apples
                String relativePath = cwd.relativize(file).toString().replace('\\', '/');
                byte[] content = Files.readAllBytes(file);
                String blobHash = store.hash(ObjectType.BLOB, content);
                result.put(relativePath, blobHash);
            }
        }

        return result;
    }

    /**
     * Reports staged changes (index vs HEAD) — what will be recorded on next commit.
     * Returns true if any output was printed.
     *
     * Three possible outcomes per path:
     *   new file  — present in index, absent from HEAD (first time this file is committed)
     *   modified  — present in both but hashes differ (file content changed since last commit)
     *   deleted   — present in HEAD but removed from index (user wants to drop the file)
     */
    private boolean reportStagedChanges(Map<String, String> indexFiles,
                                         Map<String, String> headFiles) {
        List<String> newFiles = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> deleted = new ArrayList<>();

        // Walk the index to find files that are new or modified relative to HEAD
        for (Map.Entry<String, String> entry : indexFiles.entrySet()) {
            String path = entry.getKey();
            String indexHash = entry.getValue();

            if (!headFiles.containsKey(path)) {
                // Path exists in the index but has never been committed
                newFiles.add(path);
            } else if (!indexHash.equals(headFiles.get(path))) {
                // Same path committed before, but the staged blob is different
                modified.add(path);
            }
            // Same hash → no change to report for this path
        }

        // Walk HEAD to find files that were removed from the index (staged for deletion)
        for (String path : headFiles.keySet()) {
            if (!indexFiles.containsKey(path)) {
                deleted.add(path);
            }
        }

        if (newFiles.isEmpty() && modified.isEmpty() && deleted.isEmpty()) {
            return false;
        }

        System.out.println("Changes to be committed:");
        for (String path : newFiles)  System.out.println("    new file:   " + path);
        for (String path : modified)  System.out.println("    modified:   " + path);
        for (String path : deleted)   System.out.println("    deleted:    " + path);
        System.out.println();
        return true;
    }

    /**
     * Reports unstaged changes (working dir vs index) — edits made after the last "add".
     * Returns true if any output was printed.
     *
     * Two possible outcomes per staged path:
     *   modified — the on-disk hash differs from the staged hash (file was edited)
     *   deleted  — the file no longer exists on disk (file was removed without staging the deletion)
     */
    private boolean reportUnstagedChanges(Map<String, String> workingFiles,
                                           Map<String, String> indexFiles) {
        List<String> modified = new ArrayList<>();
        List<String> deleted = new ArrayList<>();

        // For every file the index knows about, check whether disk still matches
        for (Map.Entry<String, String> entry : indexFiles.entrySet()) {
            String path = entry.getKey();
            String indexHash = entry.getValue();

            if (!workingFiles.containsKey(path)) {
                // Staged but no longer on disk — user deleted the file without staging it
                deleted.add(path);
            } else if (!indexHash.equals(workingFiles.get(path))) {
                // File exists but its content diverged from what was staged
                modified.add(path);
            }
            // Hashes match → working copy is identical to staged version; nothing to report
        }

        if (modified.isEmpty() && deleted.isEmpty()) {
            return false;
        }

        System.out.println("Changes not staged for commit:");
        for (String path : modified) System.out.println("    modified:   " + path);
        for (String path : deleted)  System.out.println("    deleted:    " + path);
        System.out.println();
        return true;
    }

    /**
     * Reports untracked files — files on disk that blobvault has never seen.
     * Returns true if any output was printed.
     *
     * A file is untracked if it appears in the working directory but has no
     * corresponding entry in the index. The user must run "add" to start tracking it.
     */
    private boolean reportUntrackedFiles(Map<String, String> workingFiles,
                                          Map<String, String> indexFiles) {
        List<String> untracked = new ArrayList<>();

        // Any working-directory path absent from the index is unknown to blobvault
        for (String path : workingFiles.keySet()) {
            if (!indexFiles.containsKey(path)) {
                untracked.add(path);
            }
        }

        if (untracked.isEmpty()) {
            return false;
        }

        System.out.println("Untracked files:");
        for (String path : untracked) System.out.println("    " + path);
        System.out.println();
        return true;
    }

    /**
     * Returns true if the file's path passes through any ignored directory.
     *
     * We inspect every component of the relative path (not just the top-level
     * directory) so that nested structures like "a/.blobvault/b" are also excluded.
     */
    private boolean isIgnored(Path cwd, Path file) {
        Path relative = cwd.relativize(file);
        for (Path component : relative) {
            if (IGNORED.contains(component.toString())) {
                return true;
            }
        }
        return false;
    }
}
