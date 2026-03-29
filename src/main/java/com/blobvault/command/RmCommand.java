package com.blobvault.command;

import com.blobvault.object.ObjectType;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.Index;
import com.blobvault.storage.IndexEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Removes files from the index and optionally from the working directory.
 * Equivalent to: git rm
 *
 * Usage:
 *   blobvault rm {@literal <path>}            → remove from index AND delete from disk
 *   blobvault rm --cached {@literal <path>}   → remove from index only (keep on disk)
 *
 * Safety: refuses to remove a file with unstaged changes (disk differs from index)
 * unless --cached is used, to prevent accidental data loss.
 */
public class RmCommand implements Command {

    @Override
    public String name() { return "rm"; }

    @Override
    public String usage() { return "rm [--cached] <path>..."; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        boolean cached = false;
        List<String> paths = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            if ("--cached".equals(args[i])) {
                cached = true;
            } else {
                paths.add(args[i]);
            }
        }

        if (paths.isEmpty()) {
            System.err.println("Usage: blobvault " + usage());
            return;
        }

        BlobStore store = new BlobStore(cwd);
        Index index = new Index(cwd);
        TreeMap<String, IndexEntry> entries = index.read();

        List<String> removed = new ArrayList<>();

        for (String path : paths) {
            IndexEntry entry = entries.get(path);
            if (entry == null) {
                System.err.println("pathspec '" + path + "' did not match any files known to blobvault");
                return;
            }

            // Safety check: refuse to remove files with unstaged changes (unless --cached)
            if (!cached) {
                Path filePath = cwd.resolve(path);
                if (Files.exists(filePath)) {
                    byte[] diskContent = Files.readAllBytes(filePath);
                    String diskHash = store.hash(ObjectType.BLOB, diskContent);
                    if (!diskHash.equals(entry.hash())) {
                        System.err.println("error: the following file has local modifications:");
                        System.err.println("    " + path);
                        System.err.println("Use --cached to keep the file, or commit/stash changes first.");
                        return;
                    }
                }
            }

            entries.remove(path);
            removed.add(path);

            // Delete from disk unless --cached
            if (!cached) {
                Path filePath = cwd.resolve(path);
                Files.deleteIfExists(filePath);
                cleanEmptyDirectories(filePath.getParent(), cwd);
            }
        }

        index.write(entries);

        for (String path : removed) {
            System.out.println("rm '" + path + "'");
        }
    }

    /**
     * Walks upward from {@code dir} toward {@code root}, removing empty directories.
     */
    private void cleanEmptyDirectories(Path dir, Path root) throws IOException {
        Path current = dir;
        while (current != null && current.startsWith(root) && !current.equals(root)) {
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isEmpty()) {
                    Files.delete(current);
                    current = current.getParent();
                } else {
                    break;
                }
            }
        }
    }
}
