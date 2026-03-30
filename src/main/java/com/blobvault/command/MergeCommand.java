package com.blobvault.command;

import com.blobvault.model.CommitObject;
import com.blobvault.model.MergeResult;
import com.blobvault.model.ObjectType;
import com.blobvault.service.CommitSerializer;
import com.blobvault.service.MergeBase;
import com.blobvault.service.ThreeWayMerge;
import com.blobvault.service.TreeWriter;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.Index;
import com.blobvault.model.IndexEntry;
import com.blobvault.storage.RefManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Merges another branch into the current branch using three-way merge.
 * Equivalent to: git merge {@literal <branch>}
 *
 * The merge process:
 *   1. Find the merge base (most recent common ancestor) of HEAD and the target branch
 *   2. Flatten the three trees: base, ours (HEAD), theirs (target branch)
 *   3. For each file present in any tree, perform a three-way merge
 *   4. If all files merge cleanly → create a merge commit with two parents
 *   5. If conflicts exist → write conflicted files to disk, write MERGE_HEAD, abort commit
 *
 * Special cases:
 *   - Already up to date: target is an ancestor of HEAD → nothing to do
 *   - Fast-forward: HEAD is an ancestor of target → just advance the branch pointer
 */
public class MergeCommand implements Command {

    @Override
    public String name() { return "merge"; }

    @Override
    public String usage() { return "merge <branch>"; }

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

        String oursHash = refs.resolveHead();
        if (oursHash == null) {
            System.err.println("Cannot merge: no commits on current branch.");
            return;
        }

        String theirsHash = refs.resolveRef(branchName);
        if (theirsHash == null) {
            System.err.println("Branch '" + branchName + "' not found.");
            return;
        }

        if (oursHash.equals(theirsHash)) {
            System.out.println("Already up to date.");
            return;
        }

        // Find the merge base — the most recent common ancestor
        String baseHash = MergeBase.find(oursHash, theirsHash, store);

        // Already up to date: theirs is an ancestor of ours
        if (baseHash != null && baseHash.equals(theirsHash)) {
            System.out.println("Already up to date.");
            return;
        }

        // Fast-forward: ours is an ancestor of theirs — just advance the pointer
        if (baseHash != null && baseHash.equals(oursHash)) {
            fastForward(cwd, branchName, theirsHash, refs, store, index);
            return;
        }

        // True merge — three-way merge required
        threeWayMerge(cwd, branchName, oursHash, theirsHash, baseHash, refs, store, index);
    }

    /**
     * Fast-forward merge: advances the current branch to the target commit
     * and updates the working tree + index to match.
     */
    private void fastForward(Path cwd, String branchName, String theirsHash,
                              RefManager refs, BlobStore store, Index index) throws Exception {
        CommitObject theirsCommit = CommitSerializer.deserialize(store.read(theirsHash));
        Map<String, String> targetFiles = TreeWriter.flattenTree(theirsCommit.treeHash(), store);

        // Update working directory
        updateWorkingTree(cwd, targetFiles, store);

        // Rebuild index from target tree
        TreeMap<String, IndexEntry> newIndex = buildIndexFromFiles(targetFiles);
        index.write(newIndex);

        // Advance the branch pointer
        String currentRef = refs.readSymbolicRef();
        refs.updateRef(currentRef, theirsHash);

        System.out.println("Fast-forward merge to " + theirsHash.substring(0, 7));
    }

    /**
     * Three-way merge: diffs base→ours and base→theirs for every file,
     * merges the changes, and either commits or reports conflicts.
     */
    private void threeWayMerge(Path cwd, String branchName,
                                String oursHash, String theirsHash, String baseHash,
                                RefManager refs, BlobStore store, Index index) throws Exception {
        // Flatten all three trees into {path → blobHash} maps
        Map<String, String> baseFiles = (baseHash != null)
                ? TreeWriter.flattenTree(CommitSerializer.deserialize(store.read(baseHash)).treeHash(), store)
                : Map.of();
        Map<String, String> oursFiles = TreeWriter.flattenTree(
                CommitSerializer.deserialize(store.read(oursHash)).treeHash(), store);
        Map<String, String> theirsFiles = TreeWriter.flattenTree(
                CommitSerializer.deserialize(store.read(theirsHash)).treeHash(), store);

        // Collect all file paths from all three trees
        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(baseFiles.keySet());
        allPaths.addAll(oursFiles.keySet());
        allPaths.addAll(theirsFiles.keySet());

        String currentBranch = refs.currentBranch();
        String oursLabel = (currentBranch != null) ? currentBranch : "HEAD";

        Map<String, byte[]> mergedFiles = new TreeMap<>();
        boolean hasConflicts = false;
        List<String> conflictedPaths = new ArrayList<>();

        for (String path : allPaths) {
            String baseBlob = baseFiles.get(path);
            String oursBlob = oursFiles.get(path);
            String theirsBlob = theirsFiles.get(path);

            MergeFileResult fileResult = mergeFile(
                    path, baseBlob, oursBlob, theirsBlob,
                    oursLabel, branchName, store);

            if (fileResult.content != null) {
                mergedFiles.put(path, fileResult.content);
            }
            // null content means file was deleted by both sides

            if (fileResult.conflict) {
                hasConflicts = true;
                conflictedPaths.add(path);
            }
        }

        // Write merged files to working directory and update index
        writeResults(cwd, mergedFiles, store, index);

        if (hasConflicts) {
            // Write MERGE_HEAD so that the next commit knows to create a merge commit
            Path mergeHeadFile = cwd.resolve(".blobvault").resolve("MERGE_HEAD");
            Files.writeString(mergeHeadFile, theirsHash + "\n");

            System.out.println("Merge conflict in:");
            for (String path : conflictedPaths) {
                System.out.println("  " + path);
            }
            System.out.println("Resolve conflicts and then commit the result.");
        } else {
            // Clean merge — create the merge commit automatically
            createMergeCommit(cwd, branchName, oursHash, theirsHash, refs, store, index);
        }
    }

    /**
     * Merges a single file from three versions. Handles all combinations:
     *   - File exists only in ours or theirs → add
     *   - File deleted by one side, unchanged by other → delete
     *   - File deleted by one side, modified by other → conflict
     *   - File modified by both → three-way content merge
     */
    private MergeFileResult mergeFile(String path, String baseBlob, String oursBlob,
                                       String theirsBlob, String oursLabel, String theirsLabel,
                                       BlobStore store) throws IOException {
        // Both sides have the same version (or both null) — no conflict
        if (Objects.equals(oursBlob, theirsBlob)) {
            if (oursBlob != null) {
                return new MergeFileResult(store.read(oursBlob), false);
            }
            return new MergeFileResult(null, false); // deleted by both
        }

        // File added only on one side (not in base, not in the other)
        if (baseBlob == null) {
            if (oursBlob == null) {
                return new MergeFileResult(store.read(theirsBlob), false);
            }
            if (theirsBlob == null) {
                return new MergeFileResult(store.read(oursBlob), false);
            }
            // Both sides added the same file with different content — conflict
            List<String> oursLines = blobToLines(oursBlob, store);
            List<String> theirsLines = blobToLines(theirsBlob, store);
            return buildAddConflict(oursLines, theirsLines, oursLabel, theirsLabel);
        }

        // File deleted by one side
        if (oursBlob == null) {
            // Ours deleted it — if theirs didn't change it, accept deletion
            if (baseBlob.equals(theirsBlob)) {
                return new MergeFileResult(null, false);
            }
            // Ours deleted, theirs modified — conflict (modify/delete)
            List<String> theirsLines = blobToLines(theirsBlob, store);
            return buildDeleteConflict(List.of(), theirsLines, oursLabel, theirsLabel);
        }
        if (theirsBlob == null) {
            // Theirs deleted it — if ours didn't change it, accept deletion
            if (baseBlob.equals(oursBlob)) {
                return new MergeFileResult(null, false);
            }
            // Theirs deleted, ours modified — conflict (modify/delete)
            List<String> oursLines = blobToLines(oursBlob, store);
            return buildDeleteConflict(oursLines, List.of(), oursLabel, theirsLabel);
        }

        // Both sides modified — three-way content merge
        List<String> baseLines = blobToLines(baseBlob, store);
        List<String> oursLines = blobToLines(oursBlob, store);
        List<String> theirsLines = blobToLines(theirsBlob, store);

        MergeResult mergeResult = ThreeWayMerge.merge(baseLines, oursLines, theirsLines,
                oursLabel, theirsLabel);

        String merged = String.join("\n", mergeResult.lines());
        if (!mergeResult.lines().isEmpty()) {
            merged += "\n";
        }
        return new MergeFileResult(merged.getBytes(StandardCharsets.UTF_8), mergeResult.hasConflicts());
    }

    private List<String> blobToLines(String blobHash, BlobStore store) throws IOException {
        byte[] data = store.read(blobHash);
        String content = new String(data, StandardCharsets.UTF_8);
        if (content.isEmpty()) return List.of();
        // Drop trailing newline before splitting to avoid phantom empty last element
        if (content.endsWith("\n")) {
            content = content.substring(0, content.length() - 1);
        }
        return List.of(content.split("\n", -1));
    }

    private MergeFileResult buildAddConflict(List<String> oursLines, List<String> theirsLines,
                                              String oursLabel, String theirsLabel) {
        List<String> lines = new ArrayList<>();
        lines.add("<<<<<<< " + oursLabel);
        lines.addAll(oursLines);
        lines.add("=======");
        lines.addAll(theirsLines);
        lines.add(">>>>>>> " + theirsLabel);
        String content = String.join("\n", lines) + "\n";
        return new MergeFileResult(content.getBytes(StandardCharsets.UTF_8), true);
    }

    private MergeFileResult buildDeleteConflict(List<String> oursLines, List<String> theirsLines,
                                                 String oursLabel, String theirsLabel) {
        // Same as add conflict — show both sides and let the user decide
        return buildAddConflict(oursLines, theirsLines, oursLabel, theirsLabel);
    }

    /**
     * Writes merged files to the working directory, stores blobs, and rebuilds the index.
     */
    private void writeResults(Path cwd, Map<String, byte[]> mergedFiles,
                               BlobStore store, Index index) throws IOException {
        TreeMap<String, IndexEntry> newIndex = new TreeMap<>();

        for (Map.Entry<String, byte[]> entry : mergedFiles.entrySet()) {
            String path = entry.getKey();
            byte[] content = entry.getValue();

            // Write to working directory
            Path filePath = cwd.resolve(path);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, content);

            // Store as blob and add to index
            String blobHash = store.store(ObjectType.BLOB, content);
            newIndex.put(path, new IndexEntry("100644", blobHash, path));
        }

        index.write(newIndex);
    }

    /**
     * Creates a merge commit with two parents (ours and theirs).
     */
    private void createMergeCommit(Path cwd, String branchName,
                                    String oursHash, String theirsHash,
                                    RefManager refs, BlobStore store, Index index) throws Exception {
        TreeMap<String, IndexEntry> entries = index.read();
        String treeHash = TreeWriter.buildTreeFromIndex(entries, store);

        long timestamp = Instant.now().getEpochSecond();
        String identity = "BlobVault <blobvault@example.com> " + timestamp + " +0000";
        String message = "Merge branch '" + branchName + "'";

        List<String> parents = List.of(oursHash, theirsHash);
        CommitObject commit = new CommitObject(treeHash, parents, identity, identity, message);
        byte[] commitData = CommitSerializer.serialize(commit);
        String commitHash = store.store(ObjectType.COMMIT, commitData);

        String currentRef = refs.readSymbolicRef();
        refs.updateRef(currentRef, commitHash);

        System.out.println("Merge made by the 'recursive' strategy.");
        System.out.println(commitHash);
    }

    /**
     * Overwrites the working directory with files from the given map.
     * Used during fast-forward to match the target commit's tree.
     */
    private void updateWorkingTree(Path cwd, Map<String, String> targetFiles,
                                    BlobStore store) throws IOException {
        for (Map.Entry<String, String> entry : targetFiles.entrySet()) {
            Path filePath = cwd.resolve(entry.getKey());
            Files.createDirectories(filePath.getParent());
            byte[] content = store.read(entry.getValue());
            Files.write(filePath, content);
        }
    }

    private TreeMap<String, IndexEntry> buildIndexFromFiles(Map<String, String> files) {
        TreeMap<String, IndexEntry> index = new TreeMap<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            index.put(entry.getKey(), new IndexEntry("100644", entry.getValue(), entry.getKey()));
        }
        return index;
    }

    private record MergeFileResult(byte[] content, boolean conflict) {}
}
