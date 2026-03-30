package com.blobvault.command;

import com.blobvault.model.CommitObject;
import com.blobvault.model.DiffHunk;
import com.blobvault.model.IndexEntry;
import com.blobvault.model.ObjectType;
import com.blobvault.service.CommitSerializer;
import com.blobvault.service.DiffEngine;
import com.blobvault.service.TreeWriter;
import com.blobvault.service.UnifiedDiffFormatter;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.Index;
import com.blobvault.storage.RefManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Shows line-by-line differences between file versions.
 * Equivalent to: git diff
 *
 * Three modes:
 *   blobvault diff                          — working dir vs index (unstaged changes)
 *   blobvault diff --staged                 — index vs HEAD (staged changes)
 *   blobvault diff {@literal <commit1> <commit2>}   — two arbitrary commits
 *
 * For each changed file, runs the Myers diff algorithm and prints the result
 * in standard unified diff format with 3 lines of context.
 */
public class DiffCommand implements Command {

    private static final Set<String> IGNORED = Set.of(".blobvault");

    @Override
    public String name() { return "diff"; }

    @Override
    public String usage() { return "diff [--staged] [<commit1> <commit2>]"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        BlobStore store = new BlobStore(cwd);
        RefManager refs = new RefManager(cwd);
        Index index = new Index(cwd);

        if (args.length >= 3) {
            // diff <commit1> <commit2>
            diffCommits(args[1], args[2], store);
        } else if (args.length >= 2 && "--staged".equals(args[1])) {
            // diff --staged
            diffStaged(store, refs, index);
        } else {
            // diff (no args)
            diffUnstaged(cwd, store, index);
        }
    }

    /**
     * Working directory vs index — shows unstaged changes.
     * For each file in the index, compares the staged blob with the on-disk content.
     */
    private void diffUnstaged(Path cwd, BlobStore store, Index index) throws IOException {
        TreeMap<String, IndexEntry> indexEntries = index.read();
        Map<String, String> workingFiles = scanWorkingDirectory(cwd, store);

        for (IndexEntry entry : indexEntries.values()) {
            String path = entry.path();
            String indexHash = entry.hash();

            if (!workingFiles.containsKey(path)) {
                // File deleted from disk — show full removal
                List<String> oldLines = blobToLines(store, indexHash);
                printFileDiff("a/" + path, "b/" + path, oldLines, List.of());
            } else if (!indexHash.equals(workingFiles.get(path))) {
                // File modified — diff staged version vs on-disk version
                List<String> oldLines = blobToLines(store, indexHash);
                List<String> newLines = fileToLines(cwd.resolve(path));
                printFileDiff("a/" + path, "b/" + path, oldLines, newLines);
            }
        }
    }

    /**
     * Index vs HEAD — shows staged changes that will go into the next commit.
     */
    private void diffStaged(BlobStore store, RefManager refs, Index index) throws IOException {
        Map<String, String> headFiles = loadHeadFiles(store, refs);

        TreeMap<String, IndexEntry> indexEntries = index.read();
        Map<String, String> indexFiles = new TreeMap<>();
        for (IndexEntry entry : indexEntries.values()) {
            indexFiles.put(entry.path(), entry.hash());
        }

        // Collect all paths from both sides
        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(headFiles.keySet());
        allPaths.addAll(indexFiles.keySet());

        for (String path : allPaths) {
            String headHash = headFiles.get(path);
            String indexHash = indexFiles.get(path);

            if (headHash == null) {
                // New file staged
                List<String> newLines = blobToLines(store, indexHash);
                printFileDiff("/dev/null", "b/" + path, List.of(), newLines);
            } else if (indexHash == null) {
                // File deleted from index
                List<String> oldLines = blobToLines(store, headHash);
                printFileDiff("a/" + path, "/dev/null", oldLines, List.of());
            } else if (!headHash.equals(indexHash)) {
                // File modified
                List<String> oldLines = blobToLines(store, headHash);
                List<String> newLines = blobToLines(store, indexHash);
                printFileDiff("a/" + path, "b/" + path, oldLines, newLines);
            }
        }
    }

    /**
     * Diff two commits by flattening their trees and comparing file-by-file.
     */
    private void diffCommits(String hashA, String hashB, BlobStore store) throws IOException {
        Map<String, String> filesA = flattenCommit(hashA, store);
        Map<String, String> filesB = flattenCommit(hashB, store);

        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(filesA.keySet());
        allPaths.addAll(filesB.keySet());

        for (String path : allPaths) {
            String blobA = filesA.get(path);
            String blobB = filesB.get(path);

            if (blobA == null) {
                List<String> newLines = blobToLines(store, blobB);
                printFileDiff("/dev/null", "b/" + path, List.of(), newLines);
            } else if (blobB == null) {
                List<String> oldLines = blobToLines(store, blobA);
                printFileDiff("a/" + path, "/dev/null", oldLines, List.of());
            } else if (!blobA.equals(blobB)) {
                List<String> oldLines = blobToLines(store, blobA);
                List<String> newLines = blobToLines(store, blobB);
                printFileDiff("a/" + path, "b/" + path, oldLines, newLines);
            }
        }
    }

    /**
     * Runs the diff engine on two line sequences and prints the unified diff output.
     */
    private void printFileDiff(String labelA, String labelB,
                               List<String> linesA, List<String> linesB) {
        List<DiffHunk> hunks = DiffEngine.diff(linesA, linesB);
        if (hunks.isEmpty()) {
            return;
        }

        String output = UnifiedDiffFormatter.format(labelA, labelB, linesA, linesB, hunks);
        System.out.print(output);
    }

    /** Reads a blob from the object store and splits it into lines. */
    private List<String> blobToLines(BlobStore store, String blobHash) throws IOException {
        byte[] content = store.read(blobHash);
        return splitLines(new String(content, StandardCharsets.UTF_8));
    }

    /** Reads a file from disk and splits it into lines. */
    private List<String> fileToLines(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return splitLines(content);
    }

    /**
     * Splits text into lines, preserving empty trailing lines only if the
     * file doesn't end with a newline. This matches Git's line-splitting behavior.
     */
    private List<String> splitLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        // Split on newline but keep trailing empty strings with -1 limit
        String[] parts = text.split("\n", -1);
        // If the file ends with \n, the last element is empty — drop it
        if (parts.length > 0 && parts[parts.length - 1].isEmpty()) {
            return List.of(Arrays.copyOf(parts, parts.length - 1));
        }
        return List.of(parts);
    }

    /** Flattens a commit's tree into {path → blobHash}. */
    private Map<String, String> flattenCommit(String commitHash, BlobStore store) throws IOException {
        byte[] data = store.read(commitHash);
        CommitObject commit = CommitSerializer.deserialize(data);
        return TreeWriter.flattenTree(commit.treeHash(), store);
    }

    private Map<String, String> loadHeadFiles(BlobStore store, RefManager refs) throws IOException {
        String headCommit = refs.resolveHead();
        if (headCommit == null) {
            return new TreeMap<>();
        }
        return flattenCommit(headCommit, store);
    }

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
