package com.blobvault.service;

import com.blobvault.model.DiffHunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the shortest edit script between two sequences of lines
 * using the Myers diff algorithm.
 *
 * The algorithm finds the minimum number of insertions and deletions needed
 * to transform sequence A into sequence B. It works by exploring an edit graph
 * where moving right = delete a line from A, moving down = insert a line from B,
 * and moving diagonally = keep a matching line. The shortest path from (0,0) to
 * (N,M) gives the optimal diff.
 *
 * Reference: Eugene W. Myers, "An O(ND) Difference Algorithm and Its Variations" (1986)
 *
 * The result is a list of {@link DiffHunk}s — contiguous regions where the files differ.
 * Unchanged lines between hunks are not included (they become context lines during formatting).
 */
public class DiffEngine {

    /**
     * Computes the diff between two line sequences.
     *
     * @param a the original lines
     * @param b the new lines
     * @return list of hunks describing the changes, empty if files are identical
     */
    public static List<DiffHunk> diff(List<String> a, List<String> b) {
        // Find the shortest edit script as a list of edit operations
        List<Edit> edits = computeEdits(a, b);

        // Group consecutive edits into hunks
        return buildHunks(edits, a, b);
    }

    /**
     * Core Myers algorithm. Returns a list of edits (EQUAL / DELETE / INSERT)
     * representing the shortest transformation from A to B.
     *
     * The algorithm explores diagonals in the edit graph, extending the search
     * by one edit at a time (d = 0, 1, 2, ...) until it reaches the bottom-right
     * corner. Then it backtracks through the recorded states to reconstruct the
     * actual edit path.
     */
    private static List<Edit> computeEdits(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();

        // Max possible edits is N + M (delete everything + insert everything)
        int max = n + m;
        if (max == 0) {
            return List.of();
        }

        // V[k] = furthest reaching x-coordinate on diagonal k
        // Diagonal k means x - y = k. We index V as V[k + max] to handle negative k.
        int vSize = 2 * max + 1;
        int[] v = new int[vSize];

        // Store the V array snapshot at each step d for backtracking
        List<int[]> trace = new ArrayList<>();

        // Forward pass: find the shortest edit distance d
        outer:
        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());

            for (int k = -d; k <= d; k += 2) {
                // Decide whether to move down (insert) or right (delete).
                // At the boundary diagonals we have no choice; otherwise pick
                // the path that reaches further right (fewer edits).
                int x;
                if (k == -d || (k != d && v[k - 1 + max] < v[k + 1 + max])) {
                    x = v[k + 1 + max]; // move down: insert from B
                } else {
                    x = v[k - 1 + max] + 1; // move right: delete from A
                }

                int y = x - k;

                // Follow the diagonal (matching lines) as far as possible
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }

                v[k + max] = x;

                // Reached the bottom-right corner — we found the shortest path
                if (x >= n && y >= m) {
                    break outer;
                }
            }
        }

        // Backtrack through the trace to reconstruct the edit sequence
        return backtrack(trace, a, b, n, m, max);
    }

    /**
     * Reconstructs the edit sequence by walking backward through the
     * recorded V-array snapshots from the Myers forward pass.
     *
     * trace[d] holds the V array at the START of round d (before round d updates it).
     * To determine what move round d made, we inspect trace[d] — that's the state
     * the forward pass used when computing round d's moves.
     */
    private static List<Edit> backtrack(List<int[]> trace, List<String> a, List<String> b,
                                         int n, int m, int max) {
        List<Edit> edits = new ArrayList<>();
        int x = n;
        int y = m;

        for (int d = trace.size() - 1; d > 0; d--) {
            int[] vPrev = trace.get(d);
            int k = x - y;

            // Determine what move got us to diagonal k at step d
            int prevK;
            if (k == -d || (k != d && vPrev[k - 1 + max] < vPrev[k + 1 + max])) {
                prevK = k + 1; // came from above: this was an insert
            } else {
                prevK = k - 1; // came from the left: this was a delete
            }

            int prevX = vPrev[prevK + max];
            int prevY = prevX - prevK;

            // Diagonal moves (matching lines) between the edit and the previous position
            while (x > prevX && y > prevY) {
                x--;
                y--;
                edits.addFirst(new Edit(EditType.EQUAL, x, y));
            }

            // The actual edit
            if (d > 0) {
                if (x == prevX) {
                    // x didn't change → moved down → insert
                    y--;
                    edits.addFirst(new Edit(EditType.INSERT, x, y));
                } else {
                    // y didn't change → moved right → delete
                    x--;
                    edits.addFirst(new Edit(EditType.DELETE, x, y));
                }
            }
        }

        // Remaining diagonal at step 0
        while (x > 0 && y > 0) {
            x--;
            y--;
            edits.addFirst(new Edit(EditType.EQUAL, x, y));
        }

        return edits;
    }

    /**
     * Groups a flat list of edits into {@link DiffHunk}s.
     * Consecutive DELETE/INSERT edits form a single hunk. EQUAL edits
     * separate hunks (they become context lines later during formatting).
     */
    private static List<DiffHunk> buildHunks(List<Edit> edits, List<String> a, List<String> b) {
        List<DiffHunk> hunks = new ArrayList<>();

        int i = 0;
        while (i < edits.size()) {
            // Skip equal lines
            if (edits.get(i).type == EditType.EQUAL) {
                i++;
                continue;
            }

            // Collect consecutive changes into one hunk
            int startA = edits.get(i).indexA;
            int startB = edits.get(i).indexB;
            List<String> removed = new ArrayList<>();
            List<String> added = new ArrayList<>();

            while (i < edits.size() && edits.get(i).type != EditType.EQUAL) {
                Edit edit = edits.get(i);
                if (edit.type == EditType.DELETE) {
                    removed.add(a.get(edit.indexA));
                } else {
                    added.add(b.get(edit.indexB));
                }
                i++;
            }

            hunks.add(new DiffHunk(startA, startB, removed, added));
        }

        return hunks;
    }

    private enum EditType { EQUAL, DELETE, INSERT }

    private record Edit(EditType type, int indexA, int indexB) {}
}
