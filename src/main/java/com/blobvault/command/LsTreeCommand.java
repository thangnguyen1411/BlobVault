package com.blobvault.command;

import com.blobvault.storage.BlobStore;
import com.blobvault.object.TreeEntry;
import com.blobvault.object.TreeSerializer;

import java.nio.file.Path;
import java.util.List;

/**
 * Lists the contents of a tree object.
 * Equivalent to: git ls-tree <hash>
 *
 * Output format (matches Git):
 *   040000 tree <hash>\t<name>
 *   100644 blob <hash>\t<name>
 */
public class LsTreeCommand implements Command {

    @Override
    public String name() { return "ls-tree"; }

    @Override
    public String usage() { return "ls-tree <hash>"; }

    @Override
    public void execute(Path cwd, String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: blobvault " + usage());
            return;
        }

        String hash = args[1];
        BlobStore store = new BlobStore(cwd);

        byte[] treeData = store.read(hash);
        List<TreeEntry> entries = TreeSerializer.deserialize(treeData);

        for (TreeEntry entry : entries) {
            System.out.printf("%s %s %s\t%s%n",
                entry.displayMode(),
                entry.type().label(),
                entry.hash(),
                entry.name()
            );
        }
    }
}
