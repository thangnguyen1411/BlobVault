package com.blobvault.service;

import com.blobvault.model.CommitObject;
import com.blobvault.storage.BlobStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects commits between two points in history.
 *
 * Follows the first-parent chain — the same path git log --first-parent walks.
 * Used by rebase to enumerate which commits need replaying onto a new base.
 */
public class CommitWalker {

    private final BlobStore store;

    public CommitWalker(BlobStore store) {
        this.store = store;
    }

    /**
     * Collects all commits reachable from {@code to} (inclusive) back to
     * {@code from} (exclusive), following first-parent links only.
     * Returns them in chronological order (oldest first) so callers can
     * replay them in the correct sequence.
     *
     * If {@code from} is null, walks all the way to the root commit.
     */
    public List<String> collectRange(String from, String to) throws IOException {
        List<String> result = new ArrayList<>();
        String current = to;

        while (current != null && !current.equals(from)) {
            result.add(current);
            CommitObject commit = CommitSerializer.deserialize(store.read(current));
            current = commit.parentHashes().isEmpty()
                    ? null
                    : commit.parentHashes().getFirst();
        }

        Collections.reverse(result); // oldest first
        return result;
    }
}
