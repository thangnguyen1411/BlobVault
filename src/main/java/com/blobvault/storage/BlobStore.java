package com.blobvault.storage;

import com.blobvault.model.ObjectType;
import com.blobvault.util.HashUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterOutputStream;

/**
 * Stores and retrieves objects in the .blobvault/objects directory.
 *
 * Storage layout (same as Git):
 *   .blobvault/objects/<first-2-hex-chars>/<remaining-38-hex-chars>
 *
 * Data is compressed with zlib (deflate) to save disk space.
 */
public class BlobStore {

    private final Path objectsDir;

    public BlobStore(Path repoRoot) {
        this.objectsDir = repoRoot.resolve(".blobvault").resolve("objects");
    }

    /**
     * Stores an object and returns its SHA-1 hash.
     *
     * Steps:
     * 1. Wrap content with "<type> <size>\0" header
     * 2. Compute SHA-1 of the wrapped content
     * 3. Compress the wrapped content with zlib
     * 4. Write to objects/<first2>/<rest38>
     */
    public String store(ObjectType type, byte[] content) throws IOException {
        byte[] wrapped = HashUtil.wrapWithHeader(type.label(), content);
        String hash = HashUtil.sha1(wrapped);

        Path objectPath = objectPath(hash);

        // Don't overwrite if it already exists — same content = same hash
        if (!Files.exists(objectPath)) {
            Files.createDirectories(objectPath.getParent());
            Files.write(objectPath, compress(wrapped));
        }

        return hash;
    }

    /**
     * Computes the hash of content WITHOUT storing it.
     * Used by StatusCommand to check if files have changed without side effects.
     * Same algorithm as store(), just skips the write step.
     */
    public String hash(ObjectType type, byte[] content) {
        byte[] wrapped = HashUtil.wrapWithHeader(type.label(), content);
        return HashUtil.sha1(wrapped);
    }

    /**
     * Reads an object by hash and returns the raw content (without the header).
     */
    public byte[] read(String hash) throws IOException {
        Path objectPath = objectPath(hash);
        if (!Files.exists(objectPath)) {
            throw new IOException("Object not found: " + hash);
        }

        byte[] compressed = Files.readAllBytes(objectPath);
        byte[] decompressed = decompress(compressed);

        // Skip past the header ("<type> <size>\0") to get the raw content
        int nullIndex = findNullByte(decompressed);
        byte[] content = new byte[decompressed.length - nullIndex - 1];
        System.arraycopy(decompressed, nullIndex + 1, content, 0, content.length);
        return content;
    }

    /**
     * Reads the object type from the header without returning the full content.
     * Parses just the header prefix (e.g., "blob", "tree", "commit", "tag").
     */
    public String readType(String hash) throws IOException {
        Path objectPath = objectPath(hash);
        if (!Files.exists(objectPath)) {
            throw new IOException("Object not found: " + hash);
        }

        byte[] compressed = Files.readAllBytes(objectPath);
        byte[] decompressed = decompress(compressed);

        // Header format: "<type> <size>\0..." — extract the type before the first space
        int spaceIndex = 0;
        while (spaceIndex < decompressed.length && decompressed[spaceIndex] != ' ') {
            spaceIndex++;
        }
        return new String(decompressed, 0, spaceIndex, StandardCharsets.UTF_8);
    }

    /**
     * Converts a hash into the object file path.
     * "abcdef..." -> objects/ab/cdef...
     */
    private Path objectPath(String hash) {
        String dir = hash.substring(0, 2);
        String file = hash.substring(2);
        return objectsDir.resolve(dir).resolve(file);
    }

    private static byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] decompress(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InflaterOutputStream inflater = new InflaterOutputStream(out)) {
            inflater.write(data);
        }
        return out.toByteArray();
    }

    private static int findNullByte(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) return i;
        }
        throw new IllegalArgumentException("No null byte found in object data");
    }
}
