package com.blobvault.service;

import com.blobvault.model.DiffHunk;
import com.blobvault.model.MergeResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Performs a three-way merge on a single file.
 *
 * Given three versions of a file (base, ours, theirs), computes the diffs
 * base→ours and base→theirs, then walks both diff results simultaneously
 * to produce a merged output. When both sides modify the same region
 * differently, a conflict is emitted with standard markers.
 *
 * The algorithm:
 *   1. Diff base→ours and base→theirs to get two hunk lists
 *   2. Walk through the base file line by line
 *   3. At each position, check if either side has a hunk starting here
 *   4. If only one side changed a region → apply that change
 *   5. If both sides changed the same region identically → apply once
 *   6. If both sides changed the same region differently → conflict
 *   7. Unchanged lines pass through as-is
 */
public class ThreeWayMerge {

    /**
     * Merges three versions of a file's content.
     *
     * @param base   the common ancestor's lines
     * @param ours   the current branch's lines
     * @param theirs the other branch's lines
     * @param oursLabel   label for the ours side in conflict markers (e.g., "HEAD")
     * @param theirsLabel label for the theirs side in conflict markers (e.g., "feature")
     * @return merged result, possibly containing conflict markers
     */
    public static MergeResult merge(List<String> base, List<String> ours, List<String> theirs,
                                     String oursLabel, String theirsLabel) {
        // If both sides are identical to base, nothing to merge
        if (ours.equals(base) && theirs.equals(base)) {
            return new MergeResult(new ArrayList<>(base), false);
        }
        // If only one side changed, take that side
        if (ours.equals(base)) {
            return new MergeResult(new ArrayList<>(theirs), false);
        }
        if (theirs.equals(base)) {
            return new MergeResult(new ArrayList<>(ours), false);
        }
        // If both made the same changes, take either
        if (ours.equals(theirs)) {
            return new MergeResult(new ArrayList<>(ours), false);
        }

        // Both sides changed differently — need hunk-level merge
        List<DiffHunk> oursHunks = DiffEngine.diff(base, ours);
        List<DiffHunk> theirsHunks = DiffEngine.diff(base, theirs);

        return mergeHunks(base, oursHunks, theirsHunks, ours, theirs, oursLabel, theirsLabel);
    }

    /**
     * Walks through the base file applying hunks from both sides.
     *
     * Uses two indices (oi, ti) to track position in the ours/theirs hunk lists.
     * At each base position, checks whether a hunk from either side starts at or
     * before the current position. Overlapping hunks are conflicts; non-overlapping
     * ones are applied independently.
     */
    private static MergeResult mergeHunks(List<String> base,
                                           List<DiffHunk> oursHunks, List<DiffHunk> theirsHunks,
                                           List<String> ours, List<String> theirs,
                                           String oursLabel, String theirsLabel) {
        List<String> result = new ArrayList<>();
        boolean hasConflicts = false;

        int basePos = 0;
        int oi = 0; // index into oursHunks
        int ti = 0; // index into theirsHunks

        while (basePos < base.size() || oi < oursHunks.size() || ti < theirsHunks.size()) {
            DiffHunk oh = (oi < oursHunks.size()) ? oursHunks.get(oi) : null;
            DiffHunk th = (ti < theirsHunks.size()) ? theirsHunks.get(ti) : null;

            // Determine the next hunk start position from either side
            int nextOurs = (oh != null) ? oh.startA() : Integer.MAX_VALUE;
            int nextTheirs = (th != null) ? th.startA() : Integer.MAX_VALUE;

            // No more hunks — copy remaining base lines
            if (oh == null && th == null) {
                while (basePos < base.size()) {
                    result.add(base.get(basePos++));
                }
                break;
            }

            // Copy unchanged base lines up to the next hunk
            int nextHunkStart = Math.min(nextOurs, nextTheirs);
            while (basePos < nextHunkStart && basePos < base.size()) {
                result.add(base.get(basePos++));
            }

            // Check if hunks from both sides overlap
            boolean oursActive = (oh != null && oh.startA() == basePos);
            boolean theirsActive = (th != null && th.startA() == basePos);

            if (oursActive && theirsActive) {
                // Both sides have a hunk at this position — check for overlap
                int oursEnd = oh.startA() + oh.removedCount();
                int theirsEnd = th.startA() + th.removedCount();

                if (oursEnd <= th.startA()) {
                    // Ours finishes before theirs starts — no overlap, apply ours
                    result.addAll(oh.added());
                    basePos = oursEnd;
                    oi++;
                } else if (theirsEnd <= oh.startA()) {
                    // Theirs finishes before ours starts — no overlap, apply theirs
                    result.addAll(th.added());
                    basePos = theirsEnd;
                    ti++;
                } else {
                    // Overlapping regions — check if both made the same change
                    if (oh.added().equals(th.added()) && oh.removedCount() == th.removedCount()) {
                        // Same change on both sides — apply once
                        result.addAll(oh.added());
                        basePos = Math.max(oursEnd, theirsEnd);
                    } else {
                        // Genuine conflict
                        hasConflicts = true;
                        result.add("<<<<<<< " + oursLabel);
                        result.addAll(oh.added());
                        result.add("=======");
                        result.addAll(th.added());
                        result.add(">>>>>>> " + theirsLabel);
                        basePos = Math.max(oursEnd, theirsEnd);
                    }
                    oi++;
                    ti++;
                }
            } else if (oursActive) {
                // Only ours has a change here
                result.addAll(oh.added());
                basePos = oh.startA() + oh.removedCount();
                oi++;
            } else if (theirsActive) {
                // Only theirs has a change here
                result.addAll(th.added());
                basePos = th.startA() + th.removedCount();
                ti++;
            }
        }

        return new MergeResult(result, hasConflicts);
    }
}
