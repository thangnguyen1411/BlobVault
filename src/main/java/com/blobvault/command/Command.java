package com.blobvault.command;

import java.nio.file.Path;

public interface Command {
    String name();
    String usage();
    void execute(Path cwd, String[] args) throws Exception;
}
