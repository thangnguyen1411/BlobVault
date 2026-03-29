package com.blobvault;

import com.blobvault.command.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class Main {

    private static final Map<String, Command> COMMANDS = new LinkedHashMap<>();

    static {
        register(new InitCommand());
        register(new HashObjectCommand());
        register(new CatFileCommand());
        register(new WriteTreeCommand());
        register(new LsTreeCommand());
        register(new AddCommand());
        register(new StatusCommand());
        register(new CommitCommand());
        register(new LogCommand());
    }

    private static void register(Command cmd) {
        COMMANDS.put(cmd.name(), cmd);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String commandName = args[0];
        Command command = COMMANDS.get(commandName);

        if (command == null) {
            System.err.println("Unknown command: " + commandName);
            printUsage();
            return;
        }

        Path cwd = Path.of(System.getProperty("user.dir"));
        command.execute(cwd, args);
    }

    private static void printUsage() {
        System.out.println("Usage: blobvault <command> [args]");
        System.out.println();
        System.out.println("Commands:");
        COMMANDS.values().forEach(cmd ->
            System.out.printf("  %-30s%n", cmd.usage())
        );
    }
}
