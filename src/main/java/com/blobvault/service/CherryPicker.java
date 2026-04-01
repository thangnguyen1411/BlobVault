package com.blobvault.service;

import com.blobvault.model.CommitObject;
import com.blobvault.model.IndexEntry;
import com.blobvault.model.MergeResult;
import com.blobvault.model.ObjectType;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.Index;
import com.blobvault.storage.RefManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Applies a single commit's diff onto the current HEAD.
 *
 * Strategy: treat the cherry-picked commit's parent tree as the merge base,
 * the cherry-picked commit's tree as "theirs", and the current HEAD tree as
 * "ours". Run a three-way merge. If clean, create a new commit and advance
 * HEAD. If conflicted, write the conflict markers to disk and return null
 * so the caller can surface the conflict to the user.
 */
public class CherryPicker {

    private final Path cwd;
    private final BlobStore store;
    private final RefManager refs;
    private final Index index;

    public CherryPicker(Path cwd, BlobStore store, RefManager refs, Index index) {
        this.cwd = cwd;
        this.store = store;
        this.refs = refs;
        this.index = index;
    }

    /**
     * Cherry-picks the given commit onto the current HEAD.
     *
     * @param commitHash     the commit to apply
     * @param appendTrailer  if true, appends "(cherry picked from commit ...)" to message
     * @return the new commit hash if clean, or null if there were conflicts
     */
    public String cherryPick(String commitHash, boolean appendTrailer) throws IOException {
        // Load the commit to cherry-pick
        CommitObject theirs = CommitSerializer.deserialize(store.read(commitHash));

        // Base = parent tree of the cherry-picked commit (empty if root commit)
        Map<String, String> baseFiles;
        if (theirs.parentHashes().isEmpty()) {
            baseFiles = Map.of();
        } else {
            String parentHash = theirs.parentHashes().getFirst();
            CommitObject parent = CommitSerializer.deserialize(store.read(parentHash));
            baseFiles = TreeWriter.flattenTree(parent.treeHash(), store);
        }

        Map<String, String> theirsFiles = TreeWriter.flattenTree(theirs.treeHash(), store);

        // Ours = current HEAD tree
        String oursHash = refs.resolveHead();
        Map<String, String> oursFiles;
        if (oursHash == null) {
            oursFiles = Map.of();
        } else {
            CommitObject ours = CommitSerializer.deserialize(store.read(oursHash));
            oursFiles = TreeWriter.flattenTree(ours.treeHash(), store);
        }

        // Labels for conflict markers
        String currentBranch = refs.currentBranch();
        String oursLabel = (currentBranch != null) ? currentBranch : "HEAD";
        String theirsLabel = commitHash.substring(0, 7);

        // Merge all paths
        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(baseFiles.keySet());
        allPaths.addAll(oursFiles.keySet());
        allPaths.addAll(theirsFiles.keySet());

        Map<String, byte[]> mergedFiles = new TreeMap<>();
        boolean hasConflicts = false;
        List<String> conflictedPaths = new ArrayList<>();

        for (String path : allPaths) {
            String baseBlob = baseFiles.get(path);
            String oursBlob = oursFiles.get(path);
            String theirsBlob = theirsFiles.get(path);

            MergeFileResult result = mergeFile(path, baseBlob, oursBlob, theirsBlob,
                    oursLabel, theirsLabel);

            if (result.content() != null) {
                mergedFiles.put(path, result.content());
            }

            if (result.conflict()) {
                hasConflicts = true;
                conflictedPaths.add(path);
            }
        }

        // Write results to working directory and update index
        writeResults(mergedFiles);

        if (hasConflicts) {
            return null; // caller is responsible for writing CHERRY_PICK_HEAD etc.
        }

        // Build the new tree from the index
        TreeMap<String, IndexEntry> entries = index.read();
        String treeHash = TreeWriter.buildTreeFromIndex(entries, store);

        // Build message
        String message = theirs.message();
        if (appendTrailer) {
            message = message + "\n\n(cherry picked from commit " + commitHash + ")";
        }

        // Build parent list: new commit's parent is the current HEAD
        List<String> parents = new ArrayList<>();
        if (oursHash != null) {
            parents.add(oursHash);
        }

        long timestamp = Instant.now().getEpochSecond();
        String identity = "BlobVault <blobvault@example.com> " + timestamp + " +0000";

        CommitObject newCommit = new CommitObject(treeHash, parents, identity, identity, message);
        byte[] commitData = CommitSerializer.serialize(newCommit);
        String newHash = store.store(ObjectType.COMMIT, commitData);

        // Advance HEAD (works for both attached and detached HEAD)
        refs.advanceHead(newHash);

        // Rebuild working tree to reflect the new commit state
        // (already done by writeResults above, so just ensure index is consistent)

        return newHash;
    }

    /**
     * Writes merged files to the working directory and rebuilds the index.
     */
    private void writeResults(Map<String, byte[]> mergedFiles) throws IOException {
        TreeMap<String, IndexEntry> newIndex = new TreeMap<>();

        for (Map.Entry<String, byte[]> entry : mergedFiles.entrySet()) {
            String path = entry.getKey();
            byte[] content = entry.getValue();

            Path filePath = cwd.resolve(path);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, content);

            String blobHash = store.store(ObjectType.BLOB, content);
            newIndex.put(path, new IndexEntry("100644", blobHash, path));
        }

        index.write(newIndex);
    }

    // --- Three-way file merge logic ---

    private MergeFileResult mergeFile(String path, String baseBlob, String oursBlob,
                                       String theirsBlob, String oursLabel,
                                       String theirsLabel) throws IOException {
        if (Objects.equals(oursBlob, theirsBlob)) {
            if (oursBlob != null) return new MergeFileResult(store.read(oursBlob), false);
            return new MergeFileResult(null, false);
        }
        if (baseBlob == null) {
            if (oursBlob == null) return new MergeFileResult(store.read(theirsBlob), false);
            if (theirsBlob == null) return new MergeFileResult(store.read(oursBlob), false);
            List<String> oursLines = blobToLines(oursBlob);
            List<String> theirsLines = blobToLines(theirsBlob);
            return buildConflict(oursLines, theirsLines, oursLabel, theirsLabel);
        }
        if (oursBlob == null) {
            if (baseBlob.equals(theirsBlob)) return new MergeFileResult(null, false);
            return buildConflict(List.of(), blobToLines(theirsBlob), oursLabel, theirsLabel);
        }
        if (theirsBlob == null) {
            if (baseBlob.equals(oursBlob)) return new MergeFileResult(null, false);
            return buildConflict(blobToLines(oursBlob), List.of(), oursLabel, theirsLabel);
        }
        List<String> baseLines = blobToLines(baseBlob);
        List<String> oursLines = blobToLines(oursBlob);
        List<String> theirsLines = blobToLines(theirsBlob);
        MergeResult result = ThreeWayMerge.merge(baseLines, oursLines, theirsLines,
                oursLabel, theirsLabel);
        String merged = String.join("\n", result.lines());
        if (!result.lines().isEmpty()) merged += "\n";
        return new MergeFileResult(merged.getBytes(StandardCharsets.UTF_8), result.hasConflicts());
    }

    private List<String> blobToLines(String blobHash) throws IOException {
        byte[] data = store.read(blobHash);
        String content = new String(data, StandardCharsets.UTF_8);
        if (content.isEmpty()) return List.of();
        if (content.endsWith("\n")) content = content.substring(0, content.length() - 1);
        return List.of(content.split("\n", -1));
    }

    private MergeFileResult buildConflict(List<String> oursLines, List<String> theirsLines,
                                           String oursLabel, String theirsLabel) {
        List<String> lines = new ArrayList<>();
        lines.add("<<<<<<< " + oursLabel);
        lines.addAll(oursLines);
        lines.add("=======");
        lines.addAll(theirsLines);
        lines.add(">>>>>>> " + theirsLabel);
        return new MergeFileResult((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8), true);
    }

    private record MergeFileResult(byte[] content, boolean conflict) {}
}
