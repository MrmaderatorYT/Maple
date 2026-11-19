package com.ccs.maple.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SharedFolderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void scanListsNestedFilesWithRelativePaths() throws Exception {
        File root = tmp.newFolder("shared");
        Io.writeText(new File(root, "a.txt"), "hello");
        File sub = new File(root, "docs/deep");
        assertTrue(sub.mkdirs());
        Io.writeText(new File(sub, "b.txt"), "world!");

        Map<String, SharedFolder.Stamp> files = SharedFolder.scan(root);

        assertEquals(Arrays.asList("a.txt", "docs/deep/b.txt"), new ArrayList<>(files.keySet()));
        assertEquals(5, files.get("a.txt").size);
        assertEquals(6, files.get("docs/deep/b.txt").size);
    }

    @Test
    public void changedReportsNewAndModifiedButNotUntouchedOrDeleted() throws Exception {
        File root = tmp.newFolder("shared");
        File kept = new File(root, "kept.txt");
        File edited = new File(root, "edited.txt");
        File removed = new File(root, "removed.txt");
        Io.writeText(kept, "same");
        Io.writeText(edited, "v1");
        Io.writeText(removed, "bye");
        assertTrue(kept.setLastModified(1_000_000_000_000L));
        assertTrue(edited.setLastModified(1_000_000_000_000L));
        Map<String, SharedFolder.Stamp> before = SharedFolder.scan(root);

        // What the guest does while the VM runs.
        Io.writeText(edited, "version 2");
        assertTrue(edited.setLastModified(1_000_000_060_000L));
        assertTrue(removed.delete());
        assertTrue(new File(root, "new").mkdir());
        Io.writeText(new File(root, "new/created.txt"), "fresh");

        List<String> changed = SharedFolder.changed(before, SharedFolder.scan(root));

        assertEquals(Arrays.asList("edited.txt", "new/created.txt"), changed);
    }

    @Test
    public void sameSizeButNewTimeCountsAsChanged() throws Exception {
        File root = tmp.newFolder("shared");
        File f = new File(root, "f.bin");
        Io.writeText(f, "abcd");
        assertTrue(f.setLastModified(1_000_000_000_000L));
        Map<String, SharedFolder.Stamp> before = SharedFolder.scan(root);
        Io.writeText(f, "wxyz");
        assertTrue(f.setLastModified(1_000_000_002_000L));

        assertEquals(Collections.singletonList("f.bin"),
                SharedFolder.changed(before, SharedFolder.scan(root)));
    }

    @Test
    public void snapshotRoundTripKeepsNamesWithSpaces() throws Exception {
        File root = tmp.newFolder("shared");
        assertTrue(new File(root, "My Documents").mkdir());
        Io.writeText(new File(root, "My Documents/report final.txt"), "x");
        Map<String, SharedFolder.Stamp> files = SharedFolder.scan(root);
        File snap = tmp.newFile("shared.snapshot");

        SharedFolder.writeSnapshot(snap, files);
        Map<String, SharedFolder.Stamp> back = SharedFolder.readSnapshot(snap);

        assertEquals(files.keySet(), back.keySet());
        SharedFolder.Stamp a = files.get("My Documents/report final.txt");
        assertTrue(a.same(back.get("My Documents/report final.txt")));
        assertTrue(SharedFolder.changed(files, back).isEmpty());
    }

    @Test
    public void rejectsNamesThatWouldEscapeTheFolder() {
        assertTrue(SharedFolder.validName("notes.txt"));
        assertTrue(SharedFolder.validName(".hidden"));
        assertFalse(SharedFolder.validName(".."));
        assertFalse(SharedFolder.validName("."));
        assertFalse(SharedFolder.validName("a/b"));
        assertFalse(SharedFolder.validName(""));
        assertFalse(SharedFolder.validName(null));
    }
}
