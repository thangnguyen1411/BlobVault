package com.blobvault.service;

import com.blobvault.model.TreeEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Serializes and deserializes tree objects using Git's binary format.
 *
 * Git tree binary format (one entry):
 *   <mode> <name>\0<20-byte-raw-SHA-1>
 *
 * Example for a file "hello.txt" with hash "aabbcc...":
 *   "100644 hello.txt\0" + [20 raw bytes of the SHA-1]
 *
 * Note: the hash is stored as 20 RAW BYTES, not the 40-char hex string!
 * This is different from how we display it — it saves space in the binary format.
 */
public class TreeSerializer {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Serializes a list of tree entries into Git's binary tree format.
     * Entries are sorted by name before serialization (Git requirement).
     */
    public static byte[] serialize(List<TreeEntry> entries) throws IOException {
        List<TreeEntry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (TreeEntry entry : sorted) {
            // Write: mode + space
            out.write(entry.mode().getBytes(StandardCharsets.UTF_8));
            out.write(' ');

            // Write: name + null byte
            out.write(entry.name().getBytes(StandardCharsets.UTF_8));
            out.write(0);

            // Write: 20 raw bytes of the SHA-1 hash
            // "aabbccdd..." (40 hex chars) → [0xAA, 0xBB, 0xCC, 0xDD, ...] (20 bytes)
            out.write(HEX.parseHex(entry.hash()));
        }

        return out.toByteArray();
    }

    /**
     * Deserializes Git's binary tree format back into a list of TreeEntry objects.
     * The input should be the raw content (header already stripped by BlobStore.read()).
     */
    public static List<TreeEntry> deserialize(byte[] data) {
        List<TreeEntry> entries = new ArrayList<>();
        int pos = 0;

        while (pos < data.length) {
            // Read mode: bytes until we hit a space
            int spaceIndex = indexOf(data, (byte) ' ', pos);
            String mode = new String(data, pos, spaceIndex - pos, StandardCharsets.UTF_8);
            pos = spaceIndex + 1;

            // Read name: bytes until we hit a null byte
            int nullIndex = indexOf(data, (byte) 0, pos);
            String name = new String(data, pos, nullIndex - pos, StandardCharsets.UTF_8);
            pos = nullIndex + 1;

            // Read hash: next 20 raw bytes → convert to 40-char hex string
            byte[] rawHash = new byte[20];
            System.arraycopy(data, pos, rawHash, 0, 20);
            String hash = HEX.formatHex(rawHash);
            pos += 20;

            entries.add(new TreeEntry(mode, name, hash));
        }

        return entries;
    }

    private static int indexOf(byte[] data, byte target, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == target) return i;
        }
        throw new IllegalArgumentException("Byte " + target + " not found starting from index " + from);
    }
}
