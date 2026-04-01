package com.blobvault.service;

import com.blobvault.model.TagObject;

import java.nio.charset.StandardCharsets;

/**
 * Serializes and deserializes annotated tag objects using Git's plain-text format.
 *
 * Git tag object format:
 *
 *   object <object-hash>\n
 *   type <object-type>\n
 *   tag <tag-name>\n
 *   tagger <name> <<email>> <timestamp> <timezone>\n
 *   \n
 *   <tag message>\n
 *
 * Same pattern as CommitSerializer — plain-text headers separated from the
 * message body by a blank line.
 */
public class TagSerializer {

    /**
     * Converts a TagObject into the plain-text body bytes.
     * BlobStore will wrap this with "tag <size>\0" when storing.
     */
    public static byte[] serialize(TagObject tag) {
        StringBuilder sb = new StringBuilder();

        sb.append("object ").append(tag.objectHash()).append('\n');
        sb.append("type ").append(tag.objectType()).append('\n');
        sb.append("tag ").append(tag.tagName()).append('\n');
        sb.append("tagger ").append(tag.tagger()).append('\n');

        // blank line separates headers from message
        sb.append('\n');
        sb.append(tag.message()).append('\n');

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses a plain-text tag body back into a TagObject.
     * Same approach as CommitSerializer: scan headers until blank line,
     * everything after is the message.
     */
    public static TagObject deserialize(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);

        String objectHash = null;
        String objectType = null;
        String tagName = null;
        String tagger = null;

        int lineIndex = 0;

        while (lineIndex < lines.length) {
            String line = lines[lineIndex];

            if (line.isEmpty()) {
                lineIndex++;
                break;
            }

            if (line.startsWith("object ")) {
                objectHash = line.substring("object ".length());
            } else if (line.startsWith("type ")) {
                objectType = line.substring("type ".length());
            } else if (line.startsWith("tag ")) {
                tagName = line.substring("tag ".length());
            } else if (line.startsWith("tagger ")) {
                tagger = line.substring("tagger ".length());
            }

            lineIndex++;
        }

        // Everything remaining is the message
        StringBuilder message = new StringBuilder();
        while (lineIndex < lines.length) {
            if (message.length() > 0) message.append('\n');
            message.append(lines[lineIndex]);
            lineIndex++;
        }

        String msg = message.toString();
        if (msg.endsWith("\n")) {
            msg = msg.substring(0, msg.length() - 1);
        }

        return new TagObject(objectHash, objectType, tagName, tagger, msg);
    }
}
