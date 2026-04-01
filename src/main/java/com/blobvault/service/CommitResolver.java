package com.blobvault.service;

import com.blobvault.model.CommitObject;
import com.blobvault.model.TagObject;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.RefManager;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves commit expressions to 40-character SHA-1 hashes.
 *
 * Supports:
 *   "HEAD"          → current branch tip
 *   "HEAD~3"        → 3 first-parents back from HEAD
 *   "main"          → branch name → its tip commit
 *   "v1.0"          → tag name → dereferenced to commit hash
 *   "abc123def..."  → raw 40-char hash (returned as-is)
 *
 * Used by ResetCommand and will be reused by future commands (rebase, cherry-pick, log).
 */
public class CommitResolver {

    private static final Pattern HEAD_TILDE = Pattern.compile("^HEAD~(\\d+)$");
    private static final Pattern HEX_40 = Pattern.compile("^[0-9a-f]{40}$");

    private final RefManager refs;
    private final BlobStore store;

    public CommitResolver(RefManager refs, BlobStore store) {
        this.refs = refs;
        this.store = store;
    }

    /**
     * Resolves a commit expression to a hash.
     *
     * @return 40-char hex hash, or {@code null} if the expression cannot be resolved
     */
    public String resolve(String expr) throws IOException {
        if (expr == null || expr.isBlank()) {
            return null;
        }

        // Raw 40-char hash — return as-is
        if (HEX_40.matcher(expr).matches()) {
            return expr;
        }

        // HEAD (exact)
        if ("HEAD".equals(expr)) {
            return refs.resolveHead();
        }

        // HEAD~N — walk N first-parents back
        Matcher tildeMatcher = HEAD_TILDE.matcher(expr);
        if (tildeMatcher.matches()) {
            int steps = Integer.parseInt(tildeMatcher.group(1));
            return walkParents(refs.resolveHead(), steps);
        }

        // Branch name
        String branchHash = refs.resolveRef(expr);
        if (branchHash != null) {
            return branchHash;
        }

        // Tag name — dereference annotated tags to get the underlying commit
        String tagHash = refs.resolveTag(expr);
        if (tagHash != null) {
            return dereferenceTag(tagHash);
        }

        return null;
    }

    /**
     * If the hash points to a tag object (annotated tag), follows it to the underlying commit.
     * If it already points to a commit (lightweight tag), returns it as-is.
     */
    private String dereferenceTag(String hash) throws IOException {
        String type = store.readType(hash);
        if ("tag".equals(type)) {
            TagObject tag = TagSerializer.deserialize(store.read(hash));
            return tag.objectHash();
        }
        return hash;
    }

    /**
     * Walks the first-parent chain from {@code startHash} for {@code steps} hops.
     * Returns {@code null} if the chain is shorter than requested.
     */
    private String walkParents(String startHash, int steps) throws IOException {
        String current = startHash;

        for (int i = 0; i < steps; i++) {
            if (current == null) return null;

            byte[] data = store.read(current);
            CommitObject commit = CommitSerializer.deserialize(data);

            if (commit.parentHashes().isEmpty()) {
                return null; // ran out of history
            }
            current = commit.parentHashes().getFirst();
        }

        return current;
    }
}
