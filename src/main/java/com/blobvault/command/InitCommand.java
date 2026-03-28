package com.blobvault.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Initializes a new BlobVault repository by creating the directory structure:
 *
 *   .blobvault/
 *     objects/       <- where blobs, trees, commits are stored
 *     refs/
 *       heads/       <- branch pointers (used in Phase 5)
 *     HEAD           <- points to the current branch (used in Phase 5)
 */
public class InitCommand implements Command {

    @Override
    public String name() { return "init"; }

    @Override
    public String usage() { return "init"; }

    @Override
    public void execute(Path cwd, String[] args) throws IOException {
        Path blobvault = cwd.resolve(".blobvault");

        if (Files.exists(blobvault)) {
            System.out.println("Already initialized: " + blobvault);
            return;
        }

        Files.createDirectories(blobvault.resolve("objects"));
        Files.createDirectories(blobvault.resolve("refs").resolve("heads"));
        Files.writeString(blobvault.resolve("HEAD"), "ref: refs/heads/main\n");

        System.out.println("Initialized empty BlobVault repository in " + blobvault);
    }
}
