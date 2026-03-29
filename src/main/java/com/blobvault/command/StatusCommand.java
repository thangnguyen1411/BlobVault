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
 * Compares three snapshots to answer three questions:
 *   Index vs HEAD        → "what will change in the next commit?"
 *   Working dir vs Index → "what have I edited but not yet staged?"
 *   Working dir \ Index  → "what files does blobvault not know about?"
 *
 * Reports:
 *   - "Changes to be committed"        (index vs HEAD)
 *   - "Changes not staged for commit"  (working dir vs index)
 *   - "Untracked files"                (in working dir but not in index)
 */
public class StatusCommand implements Command {

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

        // Normalise all three states to {path → blobHash} maps for uniform comparison
        Map<String, String> headFiles = loadHeadFiles(store, refs);

        TreeMap<String, IndexEntry> indexEntries = index.read();
        Map<String, String> indexFiles = new TreeMap<>();
        for (IndexEntry entry : indexEntries.values()) {
            indexFiles.put(entry.path(), entry.hash());
        }

        Map<String, String> workingFiles = scanWorkingDirectory(cwd, store);

        boolean anyOutput = false;
        anyOutput |= reportStagedChanges(indexFiles, headFiles);
        anyOutput |= reportUnstagedChanges(workingFiles, indexFiles);
        anyOutput |= reportUntrackedFiles(workingFiles, indexFiles);

        if (!anyOutput) {
            System.out.println("nothing to commit, working tree clean");
        }
    }

    /**
     * Flattens the HEAD commit's tree into a {path → blobHash} map.
     * Returns an empty map when no commits exist yet (fresh repo).
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
     * Reports staged changes (index vs HEAD).
     *
     * Per path:
     *   new file — in index but not in HEAD
     *   modified — in both but different hashes
     *   deleted  — in HEAD but removed from index
     *
     * @return true if any output was printed
     */
    private boolean reportStagedChanges(Map<String, String> indexFiles,
                                         Map<String, String> headFiles) {
        List<String> newFiles = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> deleted = new ArrayList<>();

        for (Map.Entry<String, String> entry : indexFiles.entrySet()) {
            String path = entry.getKey();
            String indexHash = entry.getValue();

            if (!headFiles.containsKey(path)) {
                newFiles.add(path);
            } else if (!indexHash.equals(headFiles.get(path))) {
                modified.add(path);
            }
        }

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
     * Reports unstaged changes (working dir vs index).
     *
     * Per staged path:
     *   modified — on-disk hash differs from index hash
     *   deleted  — file no longer exists on disk
     *
     * @return true if any output was printed
     */
    private boolean reportUnstagedChanges(Map<String, String> workingFiles,
                                           Map<String, String> indexFiles) {
        List<String> modified = new ArrayList<>();
        List<String> deleted = new ArrayList<>();

        for (Map.Entry<String, String> entry : indexFiles.entrySet()) {
            String path = entry.getKey();
            String indexHash = entry.getValue();

            if (!workingFiles.containsKey(path)) {
                deleted.add(path);
            } else if (!indexHash.equals(workingFiles.get(path))) {
                modified.add(path);
            }
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
     * Reports files on disk that have no entry in the index.
     *
     * @return true if any output was printed
     */
    private boolean reportUntrackedFiles(Map<String, String> workingFiles,
                                          Map<String, String> indexFiles) {
        List<String> untracked = new ArrayList<>();

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
