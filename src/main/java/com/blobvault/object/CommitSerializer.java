package com.blobvault.object;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes and deserializes commit objects using Git's plain-text format.
 *
 * Git commit format (plain text, unlike the binary tree format):
 *
 *   tree <tree-hash>\n
 *   parent <parent-hash>\n       ← one line per parent; omitted for first commit
 *   author <name> <<email>> <timestamp> <timezone>\n
 *   committer <name> <<email>> <timestamp> <timezone>\n
 *   \n                            ← blank line separates headers from message
 *   <commit message>\n
 *
 * This text body is what gets stored. The "commit <size>\0" header wrapper
 * is added by BlobStore.store() — we don't handle that here.
 */
public class CommitSerializer {

    /**
     * Converts a CommitObject into the plain-text body bytes.
     * BlobStore will wrap this with "commit <size>\0" when storing.
     */
    public static byte[] serialize(CommitObject commit) {
        StringBuilder sb = new StringBuilder();

        // tree line — always exactly one
        sb.append("tree ").append(commit.treeHash()).append('\n');

        // parent lines — zero for first commit, one for normal, multiple for merges
        for (String parent : commit.parentHashes()) {
            sb.append("parent ").append(parent).append('\n');
        }

        // author and committer lines
        sb.append("author ").append(commit.author()).append('\n');
        sb.append("committer ").append(commit.committer()).append('\n');

        // blank line separates headers from message body
        sb.append('\n');

        // commit message
        sb.append(commit.message()).append('\n');

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses a plain-text commit body (header already stripped by BlobStore.read())
     * back into a CommitObject.
     *
     * Walks through lines sequentially:
     *   "tree ..."      → tree hash
     *   "parent ..."    → collect into parent list (can appear 0+ times)
     *   "author ..."    → author line
     *   "committer ..." → committer line
     *   (blank line)    → everything after is the message
     */
    public static CommitObject deserialize(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1); // -1 preserves trailing empty strings

        String treeHash = null;
        List<String> parentHashes = new ArrayList<>();
        String author = null;
        String committer = null;

        int lineIndex = 0;

        // Parse header lines until we hit a blank line
        while (lineIndex < lines.length) {
            String line = lines[lineIndex];

            // Blank line = end of headers, start of message
            if (line.isEmpty()) {
                lineIndex++;
                break;
            }

            if (line.startsWith("tree ")) {
                treeHash = line.substring("tree ".length());
            } else if (line.startsWith("parent ")) {
                parentHashes.add(line.substring("parent ".length()));
            } else if (line.startsWith("author ")) {
                author = line.substring("author ".length());
            } else if (line.startsWith("committer ")) {
                committer = line.substring("committer ".length());
            }

            lineIndex++;
        }

        // Everything remaining is the commit message (rejoin with newlines)
        StringBuilder message = new StringBuilder();
        while (lineIndex < lines.length) {
            if (message.length() > 0) message.append('\n');
            message.append(lines[lineIndex]);
            lineIndex++;
        }

        // Trim trailing newline that serialize() adds
        String msg = message.toString();
        if (msg.endsWith("\n")) {
            msg = msg.substring(0, msg.length() - 1);
        }

        return new CommitObject(treeHash, parentHashes, author, committer, msg);
    }
}
