package com.blobvault.command;

import com.blobvault.object.ObjectType;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.Index;
import com.blobvault.storage.IndexEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Stages files for the next commit by adding them to the index.
 * Equivalent to: git add <file-or-directory>
 *
 * What happens when you run "blobvault add hello.txt":
 *   1. Read the file content
 *   2. Store it as a blob in the object store (hash + compress)
 *   3. Add an entry to .blobvault/index: "100644 <hash> hello.txt"
 *
 * The index acts as a snapshot of what WILL go into the next commit.
 * Running "add" again on a modified file simply overwrites its entry —
 * the index always reflects the most recently staged version.
 *
 * Supports:
 *   blobvault add hello.txt        → stage one file
 *   blobvault add src/             → stage all files in a directory
 *   blobvault add .                → stage everything in the working directory
 */
public class AddCommand implements Command {

    /** Directories that should never be staged (internal repo metadata). */
    private static final Set<String> IGNORED = Set.of(".blobvault");

    @Override
    public String name() { return "add"; }

    @Override
    public String usage() { return "add <file-or-directory>"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: blobvault " + usage());
            return;
        }

        // --- Load existing index so new entries are merged in, not replaced ---
        // Any file already staged keeps its entry unless we overwrite it below.
        BlobStore store = new BlobStore(cwd);
        Index index = new Index(cwd);
        TreeMap<String, IndexEntry> entries = index.read();

        // --- Resolve and validate the target path ---
        Path target = cwd.resolve(args[1]).normalize();

        if (!Files.exists(target)) {
            System.err.println("Path not found: " + args[1]);
            return;
        }

        // --- Stage: dispatch to single-file or recursive-directory handler ---
        if (Files.isRegularFile(target)) {
            addFile(cwd, target, store, entries);
        } else if (Files.isDirectory(target)) {
            addDirectory(cwd, target, store, entries);
        }

        // --- Persist the updated index to disk ---
        // The entire file is rewritten each time; entries are sorted by path.
        index.write(entries);
    }

    /**
     * Stages a single file: hash it, store the blob, add to index.
     *
     * If the file was already staged, its entry is overwritten. This makes
     * "add" idempotent and handles the "re-stage after edit" case correctly.
     */
    private void addFile(Path cwd, Path file, BlobStore store,
                         TreeMap<String, IndexEntry> entries) throws IOException {
        // --- Hash content and store as a blob object ---
        // The blob is content-addressed, so identical files share one object.
        byte[] content = Files.readAllBytes(file);
        String blobHash = store.store(ObjectType.BLOB, content);

        // --- Build the index key: repo-relative path with "/" separators ---
        // Windows uses "\"; we normalise so the index format is OS-independent.
        String relativePath = cwd.relativize(file).toString().replace('\\', '/');

        // --- Update the index entry (overwrites any previous staging of this path) ---
        entries.put(relativePath, new IndexEntry("100644", blobHash, relativePath));
    }

    /**
     * Recursively stages all regular files under a directory.
     *
     * Uses Files.walk which performs a depth-first traversal.
     * Non-file entries (directories, symlinks) and ignored paths are skipped.
     */
    private void addDirectory(Path cwd, Path directory, BlobStore store,
                              TreeMap<String, IndexEntry> entries) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path file : walk.toList()) {
                // We only stage regular files; skip directory entries themselves
                if (!Files.isRegularFile(file)) continue;

                // Skip anything whose path passes through an ignored directory
                // (e.g., .blobvault/objects/... should never be staged)
                if (isIgnored(cwd, file)) continue;

                addFile(cwd, file, store, entries);
            }
        }
    }

    /**
     * Returns true if the file lives inside any directory listed in IGNORED.
     *
     * We walk every component of the relative path rather than just the first
     * level so that nested ignored directories (e.g., a/.blobvault/b) are also
     * caught.
     */
    private boolean isIgnored(Path cwd, Path file) {
        Path relative = cwd.relativize(file);
        for (Path component : relative) {
            if (IGNORED.contains(component.toString())) {
                return true;
            }
        }
        return false;
    }
}
