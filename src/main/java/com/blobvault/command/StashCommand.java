package com.blobvault.command;

import com.blobvault.model.CommitObject;
import com.blobvault.model.ObjectType;
import com.blobvault.service.CommitSerializer;
import com.blobvault.service.TreeSerializer;
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
import java.util.stream.Stream;

/**
 * Saves and restores work-in-progress changes.
 * Equivalent to: git stash
 *
 * Internally, each stash entry is a commit with two parents:
 *   Parent 1: HEAD at the time of stash (context)
 *   Parent 2: a commit capturing the index state
 *   The stash commit's own tree: the working directory state
 *
 * This means stash reuses the same object model as everything else —
 * commits, trees, and blobs. The stash stack is stored as a simple
 * text file at .blobvault/stash-list (one hash + message per line).
 *
 * Usage:
 *   blobvault stash [push] [-m {@literal <message>}]  — save WIP and reset to HEAD
 *   blobvault stash list                               — show all stash entries
 *   blobvault stash pop                                — apply most recent stash and remove it
 *   blobvault stash apply [stash@{N}]                  — apply a stash without removing
 *   blobvault stash drop [stash@{N}]                   — remove a stash entry
 *   blobvault stash show [stash@{N}]                   — show which files changed
 */
public class StashCommand implements Command {

    private static final Set<String> IGNORED = Set.of(".blobvault");

    @Override
    public String name() { return "stash"; }

    @Override
    public String usage() { return "stash [push|pop|apply|list|drop|show] [-m <message>]"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        BlobStore store = new BlobStore(cwd);
        RefManager refs = new RefManager(cwd);
        Index index = new Index(cwd);

        String subcommand = (args.length < 2) ? "push" : args[1];

        // "stash -m <msg>" is shorthand for "stash push -m <msg>"
        if (subcommand.equals("-m")) {
            subcommand = "push";
        }

        switch (subcommand) {
            case "push" -> stashPush(cwd, args, store, refs, index);
            case "list" -> stashList(cwd);
            case "pop" -> stashPop(cwd, args, store, refs, index);
            case "apply" -> stashApply(cwd, args, store, refs, index, false);
            case "drop" -> stashDrop(cwd, args);
            case "show" -> stashShow(cwd, args, store);
            default -> System.err.println("Unknown stash subcommand: " + subcommand
                    + "\nUsage: blobvault " + usage());
        }
    }

    /**
     * Saves the current working tree and index state, then resets to HEAD.
     *
     * Creates two internal commits:
     *   1. Index commit — tree built from current index, parent is HEAD
     *   2. Stash commit — tree built from working directory, parents are [HEAD, indexCommit]
     *
     * Then resets both index and working tree to HEAD (like reset --hard).
     */
    private void stashPush(Path cwd, String[] args, BlobStore store,
                            RefManager refs, Index index) throws Exception {
        String headHash = refs.resolveHead();
        if (headHash == null) {
            System.err.println("Cannot stash: no commits yet.");
            return;
        }

        // Check if there's anything to stash
        TreeMap<String, IndexEntry> indexEntries = index.read();
        Map<String, String> headFiles = loadHeadFiles(store, refs);
        Map<String, String> indexFiles = extractHashes(indexEntries);
        Map<String, String> workingFiles = scanWorkingDirectory(cwd, store);

        if (indexFiles.equals(headFiles) && workingFiles.equals(indexFiles)) {
            System.out.println("No local changes to save.");
            return;
        }

        String message = parseStashMessage(args);
        if (message == null) {
            CommitObject headCommit = CommitSerializer.deserialize(store.read(headHash));
            String branch = refs.currentBranch();
            message = "WIP on " + (branch != null ? branch : "detached")
                    + ": " + headHash.substring(0, 7) + " " + headCommit.message();
        }

        long timestamp = Instant.now().getEpochSecond();
        String identity = "BlobVault <blobvault@example.com> " + timestamp + " +0000";

        // Create index commit: captures current staging area
        String indexTreeHash = TreeWriter.buildTreeFromIndex(indexEntries, store);
        if (indexTreeHash == null) {
            // Empty index — use an empty tree
            indexTreeHash = store.store(ObjectType.TREE, TreeSerializer.serialize(List.of()));
        }
        CommitObject indexCommit = new CommitObject(
                indexTreeHash, List.of(headHash), identity, identity, "index on " + message);
        String indexCommitHash = store.store(ObjectType.COMMIT, CommitSerializer.serialize(indexCommit));

        // Create stash commit: captures working directory state
        String workingTreeHash = TreeWriter.writeTree(cwd, store);
        if (workingTreeHash == null) {
            workingTreeHash = store.store(ObjectType.TREE, TreeSerializer.serialize(List.of()));
        }
        CommitObject stashCommit = new CommitObject(
                workingTreeHash, List.of(headHash, indexCommitHash), identity, identity, message);
        String stashHash = store.store(ObjectType.COMMIT, CommitSerializer.serialize(stashCommit));

        // Push onto stash stack
        pushStashEntry(cwd, stashHash, message);

        // Reset working tree and index to HEAD (equivalent to reset --hard HEAD)
        resetToHead(cwd, headFiles, store, index);

        System.out.println("Saved working directory and index state: " + message);
    }

    /**
     * Applies the most recent stash and removes it from the stack.
     */
    private void stashPop(Path cwd, String[] args, BlobStore store,
                           RefManager refs, Index index) throws Exception {
        stashApply(cwd, args, store, refs, index, true);
    }

    /**
     * Applies a stash entry by restoring the working tree and index state.
     * Optionally removes the entry from the stack (pop vs apply).
     */
    private void stashApply(Path cwd, String[] args, BlobStore store,
                             RefManager refs, Index index, boolean drop) throws Exception {
        List<StashEntry> stack = readStashStack(cwd);
        if (stack.isEmpty()) {
            System.err.println("No stash entries.");
            return;
        }

        int stashIndex = parseStashIndex(args, drop ? 2 : 2);
        if (stashIndex < 0 || stashIndex >= stack.size()) {
            System.err.println("stash@{" + stashIndex + "} does not exist.");
            return;
        }

        StashEntry entry = stack.get(stashIndex);
        CommitObject stashCommit = CommitSerializer.deserialize(store.read(entry.hash));

        // The stash commit's tree is the working directory state
        Map<String, String> workingState = TreeWriter.flattenTree(stashCommit.treeHash(), store);

        // The second parent is the index commit — its tree is the index state
        String indexCommitHash = stashCommit.parentHashes().get(1);
        CommitObject indexCommit = CommitSerializer.deserialize(store.read(indexCommitHash));
        Map<String, String> indexState = TreeWriter.flattenTree(indexCommit.treeHash(), store);

        // Restore index
        TreeMap<String, IndexEntry> newIndex = new TreeMap<>();
        for (Map.Entry<String, String> e : indexState.entrySet()) {
            newIndex.put(e.getKey(), new IndexEntry("100644", e.getValue(), e.getKey()));
        }
        index.write(newIndex);

        // Restore working tree
        for (Map.Entry<String, String> e : workingState.entrySet()) {
            Path filePath = cwd.resolve(e.getKey());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, store.read(e.getValue()));
        }

        if (drop) {
            stack.remove(stashIndex);
            writeStashStack(cwd, stack);
            System.out.println("Dropped stash@{" + stashIndex + "} (" + entry.hash.substring(0, 7) + ")");
        }

        System.out.println("Applied stash@{" + stashIndex + "}: " + entry.message);
    }

    /**
     * Prints all stash entries, newest first.
     */
    private void stashList(Path cwd) throws IOException {
        List<StashEntry> stack = readStashStack(cwd);
        if (stack.isEmpty()) {
            System.out.println("No stash entries.");
            return;
        }
        for (int i = 0; i < stack.size(); i++) {
            System.out.println("stash@{" + i + "}: " + stack.get(i).message);
        }
    }

    /**
     * Removes a stash entry without applying it.
     */
    private void stashDrop(Path cwd, String[] args) throws IOException {
        List<StashEntry> stack = readStashStack(cwd);
        if (stack.isEmpty()) {
            System.err.println("No stash entries.");
            return;
        }

        int stashIndex = parseStashIndex(args, 2);
        if (stashIndex < 0 || stashIndex >= stack.size()) {
            System.err.println("stash@{" + stashIndex + "} does not exist.");
            return;
        }

        StashEntry removed = stack.remove(stashIndex);
        writeStashStack(cwd, stack);
        System.out.println("Dropped stash@{" + stashIndex + "} (" + removed.hash.substring(0, 7) + ")");
    }

    /**
     * Shows which files differ between a stash entry and its parent (HEAD at stash time).
     */
    private void stashShow(Path cwd, String[] args, BlobStore store) throws IOException {
        List<StashEntry> stack = readStashStack(cwd);
        if (stack.isEmpty()) {
            System.err.println("No stash entries.");
            return;
        }

        int stashIndex = parseStashIndex(args, 2);
        if (stashIndex < 0 || stashIndex >= stack.size()) {
            System.err.println("stash@{" + stashIndex + "} does not exist.");
            return;
        }

        StashEntry entry = stack.get(stashIndex);
        CommitObject stashCommit = CommitSerializer.deserialize(store.read(entry.hash));
        String parentHash = stashCommit.parentHashes().getFirst();
        CommitObject parentCommit = CommitSerializer.deserialize(store.read(parentHash));

        Map<String, String> parentFiles = TreeWriter.flattenTree(parentCommit.treeHash(), store);
        Map<String, String> stashFiles = TreeWriter.flattenTree(stashCommit.treeHash(), store);

        // Collect all paths
        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(parentFiles.keySet());
        allPaths.addAll(stashFiles.keySet());

        for (String path : allPaths) {
            String parentBlob = parentFiles.get(path);
            String stashBlob = stashFiles.get(path);

            if (parentBlob == null) {
                System.out.println("  new file:   " + path);
            } else if (stashBlob == null) {
                System.out.println("  deleted:    " + path);
            } else if (!parentBlob.equals(stashBlob)) {
                System.out.println("  modified:   " + path);
            }
        }
    }

    // --- Stash stack persistence ---

    private record StashEntry(String hash, String message) {}

    /**
     * Reads the stash stack from .blobvault/stash-list.
     * Format: one entry per line, "hash message".
     * First entry (index 0) is the most recent.
     */
    private List<StashEntry> readStashStack(Path cwd) throws IOException {
        Path stackFile = cwd.resolve(".blobvault").resolve("stash-list");
        if (!Files.exists(stackFile)) {
            return new ArrayList<>();
        }

        List<StashEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(stackFile)) {
            if (line.isBlank()) continue;
            int space = line.indexOf(' ');
            String hash = line.substring(0, space);
            String message = line.substring(space + 1);
            entries.add(new StashEntry(hash, message));
        }
        return entries;
    }

    private void writeStashStack(Path cwd, List<StashEntry> stack) throws IOException {
        Path stackFile = cwd.resolve(".blobvault").resolve("stash-list");
        StringBuilder sb = new StringBuilder();
        for (StashEntry entry : stack) {
            sb.append(entry.hash).append(' ').append(entry.message).append('\n');
        }
        Files.writeString(stackFile, sb.toString());
    }

    private void pushStashEntry(Path cwd, String hash, String message) throws IOException {
        List<StashEntry> stack = readStashStack(cwd);
        stack.addFirst(new StashEntry(hash, message));
        writeStashStack(cwd, stack);
    }

    // --- Helpers ---

    private void resetToHead(Path cwd, Map<String, String> headFiles,
                              BlobStore store, Index index) throws IOException {
        // Rebuild index from HEAD
        TreeMap<String, IndexEntry> newIndex = new TreeMap<>();
        for (Map.Entry<String, String> entry : headFiles.entrySet()) {
            newIndex.put(entry.getKey(),
                    new IndexEntry("100644", entry.getValue(), entry.getKey()));
        }
        index.write(newIndex);

        // Restore working tree: delete non-HEAD files, write HEAD files
        Map<String, String> currentWorkingFiles = scanWorkingDirectory(cwd, store);
        for (String path : currentWorkingFiles.keySet()) {
            if (!headFiles.containsKey(path)) {
                Path filePath = cwd.resolve(path);
                Files.deleteIfExists(filePath);
                cleanEmptyDirectories(filePath.getParent(), cwd);
            }
        }
        for (Map.Entry<String, String> entry : headFiles.entrySet()) {
            Path filePath = cwd.resolve(entry.getKey());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, store.read(entry.getValue()));
        }
    }

    private Map<String, String> loadHeadFiles(BlobStore store, RefManager refs) throws IOException {
        String headHash = refs.resolveHead();
        if (headHash == null) return new TreeMap<>();
        CommitObject commit = CommitSerializer.deserialize(store.read(headHash));
        return TreeWriter.flattenTree(commit.treeHash(), store);
    }

    private Map<String, String> extractHashes(TreeMap<String, IndexEntry> entries) {
        Map<String, String> result = new TreeMap<>();
        for (IndexEntry entry : entries.values()) {
            result.put(entry.path(), entry.hash());
        }
        return result;
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

    /**
     * Parses the -m flag from stash push args.
     * Returns null if no message specified (auto-generates one).
     */
    private String parseStashMessage(String[] args) {
        for (int i = 1; i < args.length - 1; i++) {
            if ("-m".equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    /**
     * Parses stash@{N} from args at the given position, defaulting to 0.
     */
    private int parseStashIndex(String[] args, int argPos) {
        if (args.length <= argPos) return 0;

        String arg = args[argPos];
        // Support both "stash@{2}" and plain "2"
        if (arg.startsWith("stash@{") && arg.endsWith("}")) {
            return Integer.parseInt(arg.substring(7, arg.length() - 1));
        }
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
