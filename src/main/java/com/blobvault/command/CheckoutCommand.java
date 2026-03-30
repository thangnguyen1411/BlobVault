package com.blobvault.command;

import com.blobvault.model.CommitObject;
import com.blobvault.model.ObjectType;
import com.blobvault.service.CommitSerializer;
import com.blobvault.service.TreeWriter;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.Index;
import com.blobvault.model.IndexEntry;
import com.blobvault.storage.RefManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Switches the working directory to a different branch.
 * Equivalent to: git checkout {@literal <branch>}
 *
 * What happens when you run "blobvault checkout feature":
 *   1. Verify the working tree is clean (no staged or unstaged changes)
 *   2. Flatten both the current and target branch's trees into {path → hash} maps
 *   3. Diff the two maps to determine which files to delete and which to write
 *   4. Apply the diff to disk, then clean up any empty directories left behind
 *   5. Rebuild the index to match the target tree
 *   6. Update HEAD to point to the new branch
 *
 * Refuses to proceed when there are uncommitted changes.
 * Untracked files are carried across branches untouched (same as Git).
 */
public class CheckoutCommand implements Command {

    private static final Set<String> IGNORED = Set.of(".blobvault");

    @Override
    public String name() { return "checkout"; }

    @Override
    public String usage() { return "checkout <branch>"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: blobvault " + usage());
            return;
        }

        String branchName = args[1];
        BlobStore store = new BlobStore(cwd);
        RefManager refs = new RefManager(cwd);
        Index index = new Index(cwd);

        String current = refs.currentBranch();
        if (branchName.equals(current)) {
            System.out.println("Already on '" + branchName + "'");
            return;
        }

        String targetCommitHash = refs.resolveRef(branchName);
        if (targetCommitHash == null) {
            System.err.println("Branch '" + branchName + "' not found.");
            return;
        }

        if (!isWorkingTreeClean(cwd, store, refs, index)) {
            return;
        }

        // Flatten both trees into {path → blobHash} for comparison
        Map<String, String> currentFiles = loadHeadFiles(store, refs);

        byte[] commitData = store.read(targetCommitHash);
        CommitObject targetCommit = CommitSerializer.deserialize(commitData);
        Map<String, String> targetFiles = TreeWriter.flattenTree(targetCommit.treeHash(), store);

        // Files in current but not in target need to be removed from disk
        Set<String> toDelete = new LinkedHashSet<>(currentFiles.keySet());
        toDelete.removeAll(targetFiles.keySet());

        // Files new or changed in target need to be written to disk
        Map<String, String> toWrite = new TreeMap<>();
        for (Map.Entry<String, String> entry : targetFiles.entrySet()) {
            String path = entry.getKey();
            String hash = entry.getValue();
            if (!hash.equals(currentFiles.get(path))) {
                toWrite.put(path, hash);
            }
        }

        // Apply deletions, then writes
        for (String path : toDelete) {
            Files.deleteIfExists(cwd.resolve(path));
        }

        for (Map.Entry<String, String> entry : toWrite.entrySet()) {
            Path filePath = cwd.resolve(entry.getKey());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, store.read(entry.getValue()));
        }

        cleanEmptyDirectories(cwd, toDelete);

        // Rebuild the index to match the target tree so "status" shows a clean state
        TreeMap<String, IndexEntry> newIndex = new TreeMap<>();
        for (Map.Entry<String, String> entry : targetFiles.entrySet()) {
            String path = entry.getKey();
            newIndex.put(path, new IndexEntry("100644", entry.getValue(), path));
        }
        index.write(newIndex);

        refs.updateHead(branchName);
        System.out.println("Switched to branch '" + branchName + "'");
    }

    /**
     * Performs the same three-way comparison as StatusCommand to verify
     * there are no uncommitted changes that would be lost by switching.
     *
     * Checks:
     *   - Index vs HEAD        → staged changes (abort if different)
     *   - Working dir vs Index → unstaged changes (abort if different)
     *   - Untracked files are deliberately ignored (Git carries them over)
     *
     * @return true if the tree is clean and checkout can proceed
     */
    private boolean isWorkingTreeClean(Path cwd, BlobStore store,
                                       RefManager refs, Index index) throws IOException {
        Map<String, String> headFiles = loadHeadFiles(store, refs);

        TreeMap<String, IndexEntry> indexEntries = index.read();
        Map<String, String> indexFiles = new TreeMap<>();
        for (IndexEntry entry : indexEntries.values()) {
            indexFiles.put(entry.path(), entry.hash());
        }

        Map<String, String> workingFiles = scanWorkingDirectory(cwd, store);

        if (!indexFiles.equals(headFiles)) {
            System.err.println("error: Your index contains uncommitted changes.");
            System.err.println("Please commit or reset them before switching branches.");
            return false;
        }

        for (Map.Entry<String, String> entry : indexFiles.entrySet()) {
            String path = entry.getKey();
            String indexHash = entry.getValue();

            if (!workingFiles.containsKey(path) || !indexHash.equals(workingFiles.get(path))) {
                System.err.println("error: Your local changes would be overwritten by checkout.");
                System.err.println("Please commit or stash them before switching branches.");
                return false;
            }
        }

        return true;
    }

    /**
     * Flattens the HEAD commit's tree into a {path → blobHash} map.
     * Returns an empty map when no commits exist yet.
     */
    private Map<String, String> loadHeadFiles(BlobStore store, RefManager refs) throws IOException {
        String headCommit = refs.resolveHead();
        if (headCommit == null) {
            return new TreeMap<>();
        }

        byte[] commitData = store.read(headCommit);
        CommitObject commit = CommitSerializer.deserialize(commitData);
        return TreeWriter.flattenTree(commit.treeHash(), store);
    }

    /**
     * Hashes every regular file on disk (skipping .blobvault/) and returns
     * a {path → blobHash} map. Uses BlobStore.hash() to avoid writing objects.
     */
    private Map<String, String> scanWorkingDirectory(Path cwd, BlobStore store) throws IOException {
        Map<String, String> result = new TreeMap<>();

        try (Stream<Path> walk = Files.walk(cwd)) {
            for (Path file : walk.toList()) {
                if (!Files.isRegularFile(file)) continue;
                if (isIgnored(cwd, file)) continue;

                String relativePath = cwd.relativize(file).toString().replace('\\', '/');
                byte[] content = Files.readAllBytes(file);
                result.put(relativePath, store.hash(ObjectType.BLOB, content));
            }
        }

        return result;
    }

    /**
     * After deleting files, walks upward from each deleted file's parent directory
     * toward the repo root, removing any empty directories along the way.
     */
    private void cleanEmptyDirectories(Path cwd, Set<String> deletedPaths) throws IOException {
        for (String path : deletedPaths) {
            Path parent = cwd.resolve(path).getParent();

            while (parent != null && !parent.equals(cwd)) {
                if (parent.getFileName().toString().equals(".blobvault")) break;

                try (Stream<Path> entries = Files.list(parent)) {
                    if (entries.findAny().isEmpty()) {
                        Files.delete(parent);
                        parent = parent.getParent();
                    } else {
                        break;
                    }
                }
            }
        }
    }

    /** Checks whether the file's path passes through any ignored directory. */
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
