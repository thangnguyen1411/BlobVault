package com.blobvault.merge;

import java.util.List;

/**
 * Result of a three-way merge on a single file.
 *
 * If {@code hasConflicts} is false, {@code lines} contains the cleanly merged content.
 * If true, {@code lines} contains conflict markers ({@code <<<<<<<}, {@code =======},
 * {@code >>>>>>>}) embedded in the merged output — the user must resolve them manually.
 *
 * @param lines        the merged file content (may include conflict markers)
 * @param hasConflicts true if at least one region could not be auto-merged
 */
public record MergeResult(List<String> lines, boolean hasConflicts) {

    public MergeResult {
        lines = List.copyOf(lines);
    }
}
