package com.blobvault.command;

import com.blobvault.object.CommitObject;
import com.blobvault.object.CommitSerializer;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.RefManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists, creates, and deletes branches.
 * Equivalent to: git branch
 *
 * A branch is a file in .blobvault/refs/heads/ containing a commit hash.
 * Creating a branch writes a new ref file; deleting removes it.
 * The commit objects themselves are never modified.
 *
 * Usage:
 *   blobvault branch              — list all branches (* marks current)
 *   blobvault branch {@literal <name>}      — create a new branch at HEAD
 *   blobvault branch -d {@literal <name>}   — delete a fully-merged branch
 */
public class BranchCommand implements Command {

    @Override
    public String name() { return "branch"; }

    @Override
    public String usage() { return "branch [<name>] [-d <name>]"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        RefManager refs = new RefManager(cwd);

        if (args.length == 1) {
            listBranches(refs);
        } else if (args[1].equals("-d")) {
            if (args.length < 3) {
                System.err.println("Usage: blobvault branch -d <name>");
                return;
            }
            deleteBranch(args[2], cwd, refs);
        } else {
            createBranch(args[1], refs);
        }
    }

    /**
     * Prints all branches, prefixing the current one with "*".
     * If no ref files exist yet (no commits), shows the branch name from HEAD.
     */
    private void listBranches(RefManager refs) throws IOException {
        List<String> branches = refs.listBranches();
        String current = refs.currentBranch();

        if (branches.isEmpty()) {
            if (current != null) {
                System.out.println("* " + current);
            }
            return;
        }

        for (String branch : branches) {
            String prefix = branch.equals(current) ? "* " : "  ";
            System.out.println(prefix + branch);
        }
    }

    /**
     * Creates a new branch pointing to the same commit as HEAD.
     * Validates the branch name, checks for duplicates, and requires at least one commit.
     */
    private void createBranch(String name, RefManager refs) throws IOException {
        if (!name.matches("[a-zA-Z0-9._/-]+")) {
            System.err.println("Invalid branch name: " + name);
            System.err.println("Branch names may only contain letters, digits, '.', '_', '/', '-'");
            return;
        }

        if (refs.resolveRef(name) != null) {
            System.err.println("A branch named '" + name + "' already exists.");
            return;
        }

        String headCommit = refs.resolveHead();
        if (headCommit == null) {
            System.err.println("Cannot create branch: no commits yet.");
            return;
        }

        refs.updateRef("refs/heads/" + name, headCommit);
        System.out.println("Created branch '" + name + "' at " + headCommit.substring(0, 7));
    }

    /**
     * Deletes a branch after verifying:
     *   1. It is not the current branch
     *   2. It exists
     *   3. Its tip commit is reachable from HEAD (i.e., fully merged)
     */
    private void deleteBranch(String name, Path cwd, RefManager refs) throws Exception {
        String current = refs.currentBranch();
        if (name.equals(current)) {
            System.err.println("Cannot delete branch '" + name + "': it is the current branch.");
            return;
        }

        String branchCommit = refs.resolveRef(name);
        if (branchCommit == null) {
            System.err.println("Branch '" + name + "' not found.");
            return;
        }

        BlobStore store = new BlobStore(cwd);
        if (!isReachableFromHead(branchCommit, refs, store)) {
            System.err.println("Branch '" + name + "' is not fully merged into HEAD.");
            System.err.println("If you are sure you want to delete it, merge it first.");
            return;
        }

        Path refFile = cwd.resolve(".blobvault").resolve("refs").resolve("heads").resolve(name);
        Files.delete(refFile);
        System.out.println("Deleted branch '" + name + "' (was " + branchCommit.substring(0, 7) + ").");
    }

    /**
     * Walks the first-parent chain from HEAD to check whether {@code targetHash} is reachable.
     * A full DAG traversal would be needed once merge commits are supported.
     */
    private boolean isReachableFromHead(String targetHash, RefManager refs,
                                        BlobStore store) throws IOException {
        String current = refs.resolveHead();
        Set<String> visited = new HashSet<>();

        while (current != null && visited.add(current)) {
            if (current.equals(targetHash)) {
                return true;
            }

            byte[] commitData = store.read(current);
            CommitObject commit = CommitSerializer.deserialize(commitData);

            if (commit.parentHashes().isEmpty()) {
                break;
            }
            current = commit.parentHashes().getFirst();
        }

        return false;
    }
}
