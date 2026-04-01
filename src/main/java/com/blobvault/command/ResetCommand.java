package com.blobvault.command;

import com.blobvault.model.CommitObject;
import com.blobvault.model.ObjectType;
import com.blobvault.service.CommitResolver;
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
 * Resets the current branch pointer, index, and/or working tree.
 * Equivalent to: git reset
 *
 * Two distinct modes:
 *
 * Commit-level — moves HEAD and optionally resets index/working tree:
 *   blobvault reset --soft  {@literal <target>}  → move branch pointer only
 *   blobvault reset --mixed {@literal <target>}  → move pointer + reset index (default)
 *   blobvault reset --hard  {@literal <target>}  → move pointer + reset index + reset working tree
 *   blobvault reset                               → same as reset --mixed HEAD (unstage all)
 *
 * File-level — unstages specific files (inverse of "add"):
 *   blobvault reset {@literal <path>} [{@literal <path2>} ...]  → copy HEAD's version into index
 *
 * The three modes control how far back the reset reaches:
 *   --soft:  HEAD ←── only the branch pointer moves
 *   --mixed: HEAD ←── index is rebuilt from target commit's tree
 *   --hard:  HEAD ←── index ←── working directory overwritten to match
 */
public class ResetCommand implements Command {

    private static final Set<String> IGNORED = Set.of(".blobvault");

    private enum Mode { SOFT, MIXED, HARD }

    @Override
    public String name() { return "reset"; }

    @Override
    public String usage() { return "reset [--soft|--mixed|--hard] [<commit>] | reset <path>..."; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        BlobStore store = new BlobStore(cwd);
        RefManager refs = new RefManager(cwd);
        Index index = new Index(cwd);
        CommitResolver resolver = new CommitResolver(refs, store);

        ParsedArgs parsed = parseArgs(args, resolver);

        if (parsed.fileLevel) {
            resetFiles(cwd, parsed.paths, store, refs, index);
        } else {
            resetCommit(cwd, parsed.mode, parsed.targetExpr, store, refs, index, resolver);
        }
    }

    /**
     * File-level reset: copies HEAD's version of each path into the index.
     * If a path doesn't exist in HEAD, it's removed from the index (undoing an add of a new file).
     * Working tree is never touched.
     */
    private void resetFiles(Path cwd, List<String> paths, BlobStore store,
                             RefManager refs, Index index) throws IOException {
        Map<String, String> headFiles = loadHeadFiles(store, refs);
        TreeMap<String, IndexEntry> entries = index.read();

        for (String path : paths) {
            String headHash = headFiles.get(path);
            if (headHash != null) {
                // File exists in HEAD — restore its index entry
                entries.put(path, new IndexEntry("100644", headHash, path));
            } else {
                // File doesn't exist in HEAD — remove from index (undo "add" of new file)
                entries.remove(path);
            }
        }

        index.write(entries);
        System.out.println("Unstaged changes after reset:");
        for (String path : paths) {
            System.out.println("  " + path);
        }
    }

    /**
     * Commit-level reset: moves the branch pointer and optionally resets index/working tree.
     */
    private void resetCommit(Path cwd, Mode mode, String targetExpr, BlobStore store,
                              RefManager refs, Index index,
                              CommitResolver resolver) throws Exception {
        String targetHash = resolver.resolve(targetExpr);
        if (targetHash == null) {
            System.err.println("Could not resolve '" + targetExpr + "' to a commit.");
            return;
        }

        // Verify the target is actually a valid commit
        byte[] commitData;
        try {
            commitData = store.read(targetHash);
            CommitSerializer.deserialize(commitData);
        } catch (Exception e) {
            System.err.println("Not a valid commit: " + targetHash);
            return;
        }

        // Step 1: Move the branch pointer
        String currentRef = refs.readSymbolicRef();
        refs.updateRef(currentRef, targetHash);

        // Step 2 (mixed/hard): Reset the index to match the target commit's tree
        if (mode == Mode.MIXED || mode == Mode.HARD) {
            CommitObject targetCommit = CommitSerializer.deserialize(store.read(targetHash));
            Map<String, String> targetFiles = TreeWriter.flattenTree(targetCommit.treeHash(), store);

            TreeMap<String, IndexEntry> newIndex = new TreeMap<>();
            for (Map.Entry<String, String> entry : targetFiles.entrySet()) {
                newIndex.put(entry.getKey(),
                        new IndexEntry("100644", entry.getValue(), entry.getKey()));
            }
            index.write(newIndex);

            // Step 3 (hard only): Reset the working directory
            if (mode == Mode.HARD) {
                resetWorkingTree(cwd, targetFiles, store);
            }
        }

        // Clean up MERGE_HEAD if it exists (abort any in-progress merge)
        Path mergeHead = cwd.resolve(".blobvault").resolve("MERGE_HEAD");
        if (Files.exists(mergeHead)) {
            Files.delete(mergeHead);
        }

        // Clean up CHERRY_PICK_HEAD/ORIG_HEAD if aborting an in-progress cherry-pick
        refs.deleteStateFile("CHERRY_PICK_HEAD");
        refs.deleteStateFile("ORIG_HEAD");

        System.out.println("HEAD is now at " + targetHash.substring(0, 7));
    }

    /**
     * Overwrites the working directory to exactly match the target files.
     * Deletes tracked files not in the target, writes files from the target's blobs.
     * Untracked files are preserved.
     */
    private void resetWorkingTree(Path cwd, Map<String, String> targetFiles,
                                   BlobStore store) throws IOException {
        // Scan current working dir to find files to potentially delete
        Map<String, String> currentFiles = scanWorkingDirectory(cwd, store);

        // Delete files that are tracked but not in the target
        for (String path : currentFiles.keySet()) {
            if (!targetFiles.containsKey(path)) {
                Path filePath = cwd.resolve(path);
                Files.deleteIfExists(filePath);
                cleanEmptyDirectories(filePath.getParent(), cwd);
            }
        }

        // Write/overwrite files from the target
        for (Map.Entry<String, String> entry : targetFiles.entrySet()) {
            Path filePath = cwd.resolve(entry.getKey());
            Files.createDirectories(filePath.getParent());
            byte[] content = store.read(entry.getValue());
            Files.write(filePath, content);
        }
    }

    /**
     * Walks upward from {@code dir} toward {@code root}, removing empty directories.
     */
    private void cleanEmptyDirectories(Path dir, Path root) throws IOException {
        Path current = dir;
        while (current != null && current.startsWith(root) && !current.equals(root)) {
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isEmpty()) {
                    Files.delete(current);
                    current = current.getParent();
                } else {
                    break;
                }
            }
        }
    }

    private Map<String, String> loadHeadFiles(BlobStore store, RefManager refs) throws IOException {
        String headCommit = refs.resolveHead();
        if (headCommit == null) {
            return new TreeMap<>();
        }
        CommitObject commit = CommitSerializer.deserialize(store.read(headCommit));
        return TreeWriter.flattenTree(commit.treeHash(), store);
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
            if (IGNORED.contains(component.toString())) return true;
        }
        return false;
    }

    /**
     * Parses arguments to determine whether this is a file-level or commit-level reset.
     *
     * Disambiguation logic:
     *   1. If any flag is present (--soft/--mixed/--hard) → commit-level
     *   2. If no flags and first arg resolves as a commit → commit-level with that target
     *   3. Otherwise → file-level, all args are paths
     */
    private ParsedArgs parseArgs(String[] args, CommitResolver resolver) throws IOException {
        Mode mode = null;
        List<String> remaining = new ArrayList<>();

        // Skip args[0] which is "reset"
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--soft" -> mode = Mode.SOFT;
                case "--mixed" -> mode = Mode.MIXED;
                case "--hard" -> mode = Mode.HARD;
                default -> remaining.add(args[i]);
            }
        }

        // If a mode flag was given → commit-level
        if (mode != null) {
            String target = remaining.isEmpty() ? "HEAD" : remaining.getFirst();
            return new ParsedArgs(false, mode, target, List.of());
        }

        // No flags, no remaining args → default: reset --mixed HEAD (unstage all)
        if (remaining.isEmpty()) {
            return new ParsedArgs(false, Mode.MIXED, "HEAD", List.of());
        }

        // Try to resolve the first argument as a commit
        String firstResolved = resolver.resolve(remaining.getFirst());
        if (firstResolved != null) {
            return new ParsedArgs(false, Mode.MIXED, remaining.getFirst(), List.of());
        }

        // Not a commit → treat all remaining args as file paths
        return new ParsedArgs(true, null, null, remaining);
    }

    private record ParsedArgs(boolean fileLevel, Mode mode, String targetExpr, List<String> paths) {}
}
