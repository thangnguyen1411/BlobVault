package com.blobvault.diff;

import java.util.List;

/**
 * One contiguous block of changes between two files.
 *
 * A hunk represents a region where the two files differ. It records:
 *   - Where the change starts in each file (0-based line index)
 *   - The lines removed from file A
 *   - The lines added from file B
 *
 * A pure deletion has an empty {@code added} list; a pure insertion has
 * an empty {@code removed} list; a modification has both.
 *
 * @param startA  0-based line index in the original file (A)
 * @param startB  0-based line index in the new file (B)
 * @param removed lines from A that were deleted or replaced
 * @param added   lines from B that were inserted or replaced
 */
public record DiffHunk(int startA, int startB, List<String> removed, List<String> added) {

    public DiffHunk {
        removed = List.copyOf(removed);
        added = List.copyOf(added);
    }

    /** Number of lines removed from file A. */
    public int removedCount() { return removed.size(); }

    /** Number of lines added to file B. */
    public int addedCount() { return added.size(); }
}
