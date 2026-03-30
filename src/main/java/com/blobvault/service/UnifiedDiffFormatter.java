package com.blobvault.service;

import com.blobvault.model.DiffHunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a list of {@link DiffHunk}s in the standard unified diff format.
 *
 * Unified diff output looks like:
 * <pre>
 * --- a/file.txt
 * +++ b/file.txt
 * @@ -1,4 +1,5 @@
 *  context line
 * -removed line
 * +added line
 * +another added line
 *  context line
 * </pre>
 *
 * Context lines (default 3) surround each change to show where it occurs.
 * Adjacent hunks whose context overlaps are merged into a single output block.
 */
public class UnifiedDiffFormatter {

    private static final int DEFAULT_CONTEXT = 3;

    /**
     * Formats a complete unified diff for one file.
     *
     * @param pathA    label for the original file (e.g., "a/hello.txt")
     * @param pathB    label for the new file (e.g., "b/hello.txt")
     * @param linesA   the original file's lines
     * @param linesB   the new file's lines
     * @param hunks    the diff hunks produced by {@link DiffEngine}
     * @return formatted diff as a single string, empty if no hunks
     */
    public static String format(String pathA, String pathB,
                                List<String> linesA, List<String> linesB,
                                List<DiffHunk> hunks) {
        return format(pathA, pathB, linesA, linesB, hunks, DEFAULT_CONTEXT);
    }

    /**
     * Formats a complete unified diff with a configurable number of context lines.
     */
    public static String format(String pathA, String pathB,
                                List<String> linesA, List<String> linesB,
                                List<DiffHunk> hunks, int contextLines) {
        if (hunks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(pathA).append('\n');
        sb.append("+++ ").append(pathB).append('\n');

        // Group hunks into output blocks, merging those whose context overlaps
        List<List<DiffHunk>> groups = groupHunks(hunks, contextLines, linesA.size());

        for (List<DiffHunk> group : groups) {
            formatGroup(sb, group, linesA, linesB, contextLines);
        }

        return sb.toString();
    }

    /**
     * Merges hunks whose surrounding context overlaps into groups.
     *
     * Two hunks overlap when the gap between them is less than 2 * contextLines.
     * In that case they share context and should be rendered as one @@ block.
     */
    private static List<List<DiffHunk>> groupHunks(List<DiffHunk> hunks, int contextLines,
                                                    int totalLinesA) {
        List<List<DiffHunk>> groups = new ArrayList<>();
        List<DiffHunk> current = new ArrayList<>();
        current.add(hunks.getFirst());

        for (int i = 1; i < hunks.size(); i++) {
            DiffHunk prev = hunks.get(i - 1);
            DiffHunk next = hunks.get(i);

            // End of previous hunk's context in file A
            int prevEnd = prev.startA() + prev.removedCount() + contextLines;
            // Start of next hunk's context in file A
            int nextStart = next.startA() - contextLines;

            if (prevEnd >= nextStart) {
                // Overlapping context — merge into same group
                current.add(next);
            } else {
                groups.add(current);
                current = new ArrayList<>();
                current.add(next);
            }
        }
        groups.add(current);

        return groups;
    }

    /**
     * Formats one group of merged hunks as a single @@ block.
     */
    private static void formatGroup(StringBuilder sb, List<DiffHunk> group,
                                     List<String> linesA, List<String> linesB,
                                     int contextLines) {
        DiffHunk first = group.getFirst();
        DiffHunk last = group.getLast();

        // Compute the range in file A and file B that this block covers
        int startA = Math.max(0, first.startA() - contextLines);
        int endA = Math.min(linesA.size(), last.startA() + last.removedCount() + contextLines);

        int startB = Math.max(0, first.startB() - contextLines);
        int endB = Math.min(linesB.size(), last.startB() + last.addedCount() + contextLines);

        // @@ header uses 1-based line numbers
        sb.append(formatRange(startA, endA - startA, startB, endB - startB)).append('\n');

        // Walk through file A from startA to endA, interleaving hunk content
        int posA = startA;
        int posB = startB;

        for (DiffHunk hunk : group) {
            // Context lines before this hunk
            while (posA < hunk.startA()) {
                sb.append(' ').append(linesA.get(posA)).append('\n');
                posA++;
                posB++;
            }

            // Removed lines
            for (String line : hunk.removed()) {
                sb.append('-').append(line).append('\n');
                posA++;
            }

            // Added lines
            for (String line : hunk.added()) {
                sb.append('+').append(line).append('\n');
                posB++;
            }
        }

        // Trailing context after the last hunk
        while (posA < endA) {
            sb.append(' ').append(linesA.get(posA)).append('\n');
            posA++;
            posB++;
        }
    }

    /**
     * Formats the @@ range header.
     *
     * Uses 1-based line numbers. A count of 0 means the range is empty
     * (pure insertion or pure deletion). A count of 1 is shown as just the
     * line number (e.g., "@@ -5 +5,2 @@").
     */
    private static String formatRange(int startA, int countA, int startB, int countB) {
        // Unified diff convention: 1-based line numbers.
        // count=0 → empty side, show "0,0". count=1 → show just the line number.
        String rangeA = formatSide(startA, countA);
        String rangeB = formatSide(startB, countB);
        return "@@ -" + rangeA + " +" + rangeB + " @@";
    }

    private static String formatSide(int start, int count) {
        if (count == 0) {
            return "0,0";
        } else if (count == 1) {
            return String.valueOf(start + 1);
        } else {
            return (start + 1) + "," + count;
        }
    }
}
