package com.blobvault.service;

import com.blobvault.model.CommitObject;
import com.blobvault.storage.BlobStore;

import java.io.IOException;
import java.util.*;

/**
 * Finds the merge base (most recent common ancestor) of two commits.
 *
 * Uses a simultaneous BFS from both commits. The first commit hash that
 * appears in both traversals is the merge base — the point where the
 * two lines of history diverged.
 *
 * Returns {@code null} if the commits share no common ancestor (disjoint histories).
 */
public class MergeBase {

    /**
     * Finds the merge base of two commits by simultaneous breadth-first traversal.
     *
     * Alternates BFS steps between the two commit histories. When a commit
     * visited from one side is encountered from the other, that's the merge base.
     */
    public static String find(String commitA, String commitB, BlobStore store) throws IOException {
        if (commitA.equals(commitB)) {
            return commitA;
        }

        Set<String> visitedA = new HashSet<>();
        Set<String> visitedB = new HashSet<>();
        Queue<String> queueA = new LinkedList<>();
        Queue<String> queueB = new LinkedList<>();

        visitedA.add(commitA);
        visitedB.add(commitB);
        queueA.add(commitA);
        queueB.add(commitB);

        // Check if one is already an ancestor of the other
        if (visitedB.contains(commitA)) return commitA;
        if (visitedA.contains(commitB)) return commitB;

        while (!queueA.isEmpty() || !queueB.isEmpty()) {
            // Expand one level from side A
            String result = expandLevel(queueA, visitedA, visitedB, store);
            if (result != null) return result;

            // Expand one level from side B
            result = expandLevel(queueB, visitedB, visitedA, store);
            if (result != null) return result;
        }

        return null;
    }

    /**
     * Processes one BFS level: dequeues all current-level commits, enqueues
     * their parents, and checks if any parent was already visited from the other side.
     */
    private static String expandLevel(Queue<String> queue, Set<String> visited,
                                       Set<String> otherVisited, BlobStore store) throws IOException {
        int levelSize = queue.size();
        for (int i = 0; i < levelSize; i++) {
            String current = queue.poll();
            if (current == null) break;

            byte[] data = store.read(current);
            CommitObject commit = CommitSerializer.deserialize(data);

            for (String parent : commit.parentHashes()) {
                if (otherVisited.contains(parent)) {
                    return parent;
                }
                if (visited.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return null;
    }
}
