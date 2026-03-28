package com.blobvault;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes SHA-1 hashes the same way Git does:
 * hash( "blob <size>\0<content>" )
 */
public class HashUtil {

    /**
     * Wraps raw content with a Git-style blob header.
     * Format: "blob <content-length>\0<content>"
     *
     * The \0 (null byte) separates the header from the content.
     * This is how Git distinguishes blob objects from tree/commit objects.
     */
    public static byte[] wrapWithHeader(String type, byte[] content) {
        byte[] header = (type + " " + content.length + "\0").getBytes();
        byte[] result = new byte[header.length + content.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(content, 0, result, header.length, content.length);
        return result;
    }

    /**
     * Computes the SHA-1 hash of content with a blob header.
     * Returns the hash as a 40-character hex string (just like Git).
     */
    public static String hashBlob(byte[] content) {
        byte[] wrapped = wrapWithHeader("blob", content);
        return sha1(wrapped);
    }

    /**
     * Computes raw SHA-1 hash and returns it as a hex string.
     */
    public static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is guaranteed to be available in every JVM
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
}
