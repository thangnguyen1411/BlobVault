package com.blobvault.command;

import com.blobvault.model.ObjectType;
import com.blobvault.model.TagObject;
import com.blobvault.service.CommitResolver;
import com.blobvault.service.TagSerializer;
import com.blobvault.storage.BlobStore;
import com.blobvault.storage.RefManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Lists, creates, and deletes tags.
 * Equivalent to: git tag
 *
 * Two kinds of tags:
 *   - Lightweight: a ref file in refs/tags/ pointing directly to a commit hash.
 *     No object is created. Structurally identical to a branch that never moves.
 *   - Annotated: a tag object in the object store wrapping a commit with metadata
 *     (tagger, date, message). The ref file points to the tag object hash.
 *
 * Usage:
 *   blobvault tag                              — list all tags
 *   blobvault tag {@literal <name>} [{@literal <commit>}]    — create lightweight tag
 *   blobvault tag -a {@literal <name>} -m {@literal <msg>}   — create annotated tag
 *   blobvault tag -d {@literal <name>}                        — delete a tag
 */
public class TagCommand implements Command {

    @Override
    public String name() { return "tag"; }

    @Override
    public String usage() { return "tag [<name>] [-a <name> -m <msg>] [-d <name>]"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        BlobStore store = new BlobStore(cwd);
        RefManager refs = new RefManager(cwd);
        CommitResolver resolver = new CommitResolver(refs, store);

        ParsedArgs parsed = parseArgs(args);

        switch (parsed.mode) {
            case LIST -> listTags(refs);
            case CREATE_LIGHTWEIGHT -> createLightweight(parsed, refs, resolver);
            case CREATE_ANNOTATED -> createAnnotated(parsed, refs, store, resolver);
            case DELETE -> deleteTag(parsed.name, refs);
        }
    }

    private void listTags(RefManager refs) throws Exception {
        List<String> tags = refs.listTags();
        if (tags.isEmpty()) {
            return; // Git prints nothing when no tags exist
        }
        for (String tag : tags) {
            System.out.println(tag);
        }
    }

    private void createLightweight(ParsedArgs parsed, RefManager refs,
                                    CommitResolver resolver) throws Exception {
        if (!validateTagName(parsed.name, refs)) return;

        String commitHash = resolveTarget(parsed.target, refs, resolver);
        if (commitHash == null) return;

        refs.updateTag(parsed.name, commitHash);
        System.out.println("Created tag '" + parsed.name + "' at " + commitHash.substring(0, 7));
    }

    private void createAnnotated(ParsedArgs parsed, RefManager refs, BlobStore store,
                                  CommitResolver resolver) throws Exception {
        if (!validateTagName(parsed.name, refs)) return;

        if (parsed.message == null || parsed.message.isBlank()) {
            System.err.println("Annotated tags require a message: tag -a <name> -m <message>");
            return;
        }

        String targetHash = resolveTarget(parsed.target, refs, resolver);
        if (targetHash == null) return;

        long timestamp = Instant.now().getEpochSecond();
        String identity = "BlobVault <blobvault@example.com> " + timestamp + " +0000";

        String objectType = store.readType(targetHash);
        TagObject tagObj = new TagObject(targetHash, objectType, parsed.name, identity, parsed.message);
        byte[] tagData = TagSerializer.serialize(tagObj);
        String tagHash = store.store(ObjectType.TAG, tagData);

        refs.updateTag(parsed.name, tagHash);
        System.out.println("Created annotated tag '" + parsed.name + "' at " + targetHash.substring(0, 7));
    }

    private void deleteTag(String name, RefManager refs) throws Exception {
        String tagHash = refs.resolveTag(name);
        if (tagHash == null) {
            System.err.println("Tag '" + name + "' not found.");
            return;
        }

        refs.deleteTag(name);
        System.out.println("Deleted tag '" + name + "' (was " + tagHash.substring(0, 7) + ").");
    }

    /**
     * Validates tag name format and checks for duplicates.
     * Returns false (and prints error) if invalid.
     */
    private boolean validateTagName(String name, RefManager refs) throws Exception {
        if (!name.matches("[a-zA-Z0-9._/-]+")) {
            System.err.println("Invalid tag name: " + name);
            System.err.println("Tag names may only contain letters, digits, '.', '_', '/', '-'");
            return false;
        }

        if (refs.resolveTag(name) != null) {
            System.err.println("Tag '" + name + "' already exists.");
            return false;
        }

        return true;
    }

    /**
     * Resolves the target commit. Uses HEAD if no explicit target provided.
     */
    private String resolveTarget(String target, RefManager refs,
                                  CommitResolver resolver) throws Exception {
        if (target != null) {
            String hash = resolver.resolve(target);
            if (hash == null) {
                System.err.println("Could not resolve '" + target + "' to a commit.");
                return null;
            }
            return hash;
        }

        String headHash = refs.resolveHead();
        if (headHash == null) {
            System.err.println("Cannot create tag: no commits yet.");
            return null;
        }
        return headHash;
    }

    // --- Argument parsing ---

    private enum Mode { LIST, CREATE_LIGHTWEIGHT, CREATE_ANNOTATED, DELETE }

    private record ParsedArgs(Mode mode, String name, String target, String message) {}

    /**
     * Parses tag command arguments.
     *
     * Patterns:
     *   tag                          → LIST
     *   tag -d {@literal <name>}     → DELETE
     *   tag -a {@literal <name>} -m {@literal <msg>} [{@literal <commit>}] → CREATE_ANNOTATED
     *   tag {@literal <name>} [{@literal <commit>}]   → CREATE_LIGHTWEIGHT
     */
    private ParsedArgs parseArgs(String[] args) {
        if (args.length < 2) {
            return new ParsedArgs(Mode.LIST, null, null, null);
        }

        boolean annotated = false;
        boolean delete = false;
        String name = null;
        String message = null;
        String target = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-a" -> annotated = true;
                case "-d" -> delete = true;
                case "-m" -> {
                    if (i + 1 < args.length) {
                        message = args[++i];
                    }
                }
                default -> {
                    if (name == null) {
                        name = args[i];
                    } else {
                        target = args[i]; // optional commit target
                    }
                }
            }
        }

        if (delete) {
            return new ParsedArgs(Mode.DELETE, name, null, null);
        }
        if (annotated) {
            return new ParsedArgs(Mode.CREATE_ANNOTATED, name, target, message);
        }
        if (name != null) {
            return new ParsedArgs(Mode.CREATE_LIGHTWEIGHT, name, target, null);
        }
        return new ParsedArgs(Mode.LIST, null, null, null);
    }
}
