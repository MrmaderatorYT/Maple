package com.ccs.maple.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A folder shared between the phone and a VM, without broad storage access.
 *
 * <p>The user picks a folder with the system folder picker (a document tree). QEMU can only
 * serve a real directory, so before the VM starts the tree is mirrored into the VM's private
 * {@code shared} directory, and QEMU exposes that copy as a FAT drive. When the share is
 * writable, files the guest created or changed are copied back into the picked folder after
 * the VM shuts down. Files deleted in the guest are not deleted on the phone: the phone's
 * folder stays the source of truth, and the next start mirrors it again.
 */
public final class SharedFolder {
    private static final String TAG = "MapleShare";

    /** QEMU's FAT drive is FAT16 (about 500 MB); keep some room for directory entries. */
    public static final long MAX_BYTES = 480L * 1024 * 1024;

    private static final String STAGING = "shared";
    private static final String SNAPSHOT = "shared.snapshot";

    /** Size and modification time of one staged file. */
    static final class Stamp {
        final long size;
        final long mtime;

        Stamp(long size, long mtime) {
            this.size = size;
            this.mtime = mtime;
        }

        boolean same(Stamp o) {
            return o != null && o.size == size && o.mtime == mtime;
        }
    }

    /** Thrown when the picked folder does not fit on the shared drive. */
    public static final class TooLargeException extends IOException {
        public TooLargeException(long bytes) {
            super("shared folder is " + bytes + " bytes");
        }
    }

    private SharedFolder() {
    }

    /** The private directory QEMU serves for this VM. */
    public static File stagingDir(File vmDir) {
        return new File(vmDir, STAGING);
    }

    // ------------------------------------------------------------------ phone -> VM

    /**
     * Mirrors the picked folder into the staging directory. Unchanged files are kept, so a
     * second start only copies what changed on the phone.
     */
    public static void stage(Context c, Uri tree, File vmDir) throws IOException {
        ContentResolver cr = c.getContentResolver();
        Map<String, Remote> remote = new TreeMap<>();
        long total = listTree(cr, tree, DocumentsContract.getTreeDocumentId(tree), "", remote);
        if (total > MAX_BYTES) throw new TooLargeException(total);

        File dir = stagingDir(vmDir);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);

        // Drop what no longer exists on the phone (including guest-only files from last run).
        for (Map.Entry<String, File> e : localEntries(dir).entrySet()) {
            Remote r = remote.get(e.getKey());
            File f = e.getValue();
            if (r == null || r.isDir != f.isDirectory()) Io.deleteRecursive(f);
        }
        for (Map.Entry<String, Remote> e : remote.entrySet()) {
            Remote r = e.getValue();
            File f = new File(dir, e.getKey());
            if (r.isDir) {
                if (!f.isDirectory() && !f.mkdirs()) throw new IOException("cannot create " + f);
                continue;
            }
            if (f.isFile() && f.length() == r.size && r.mtime > 0 && f.lastModified() == r.mtime) {
                continue;
            }
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, r.docId);
            try (InputStream in = cr.openInputStream(doc);
                 OutputStream out = new FileOutputStream(f)) {
                if (in == null) throw new IOException("cannot read " + e.getKey());
                Io.copy(in, out);
            }
            if (r.mtime > 0) {
                //noinspection ResultOfMethodCallIgnored
                f.setLastModified(r.mtime);
            }
        }
        writeSnapshot(new File(vmDir, SNAPSHOT), scan(dir));
    }

    // ------------------------------------------------------------------ VM -> phone

    /** Result of copying guest changes back to the phone. */
    public static final class WriteBack {
        public int copied;
        public final List<String> failed = new ArrayList<>();
    }

    /** Copies files the guest created or changed back into the picked folder. */
    public static WriteBack writeBack(Context c, Uri tree, File vmDir) {
        WriteBack result = new WriteBack();
        File dir = stagingDir(vmDir);
        File snap = new File(vmDir, SNAPSHOT);
        if (!dir.isDirectory() || !snap.isFile()) return result;
        Map<String, Stamp> before;
        try {
            before = readSnapshot(snap);
        } catch (IOException e) {
            Log.w(TAG, "snapshot unreadable", e);
            return result;
        }
        Map<String, Stamp> after = scan(dir);
        List<String> changed = changed(before, after);
        if (changed.isEmpty()) return result;

        ContentResolver cr = c.getContentResolver();
        Map<String, Map<String, String>> children = new HashMap<>();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        for (String rel : changed) {
            try {
                String[] parts = rel.split("/");
                String parentId = rootId;
                for (int i = 0; i < parts.length - 1; i++) {
                    parentId = findOrCreate(cr, tree, parentId, parts[i], true, children);
                }
                String fileId = findOrCreate(cr, tree, parentId, parts[parts.length - 1], false,
                        children);
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, fileId);
                try (InputStream in = new FileInputStream(new File(dir, rel));
                     OutputStream out = cr.openOutputStream(doc, "wt")) {
                    if (out == null) throw new IOException("cannot write");
                    Io.copy(in, out);
                }
                before.put(rel, after.get(rel));
                result.copied++;
            } catch (Exception e) {
                Log.w(TAG, "write back failed: " + rel, e);
                result.failed.add(rel);
            }
        }
        try {
            writeSnapshot(snap, before);
        } catch (IOException e) {
            Log.w(TAG, "snapshot not saved", e);
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    /** Human-readable name of the picked folder. */
    public static String displayName(Context c, Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cur = c.getContentResolver().query(doc,
                    new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) return cur.getString(0);
            }
        } catch (Exception ignored) {
            // fall through
        }
        String last = tree.getLastPathSegment();
        return last != null ? last : tree.toString();
    }

    private static final class Remote {
        final String docId;
        final boolean isDir;
        final long size;
        final long mtime;

        Remote(String docId, boolean isDir, long size, long mtime) {
            this.docId = docId;
            this.isDir = isDir;
            this.size = size;
            this.mtime = mtime;
        }
    }

    /** Lists the tree recursively; returns the total size of its files. */
    private static long listTree(ContentResolver cr, Uri tree, String parentId, String prefix,
                                 Map<String, Remote> out) throws IOException {
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        List<String[]> dirs = new ArrayList<>();
        long total = 0;
        try (Cursor cur = cr.query(uri, new String[]{
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (cur == null) throw new IOException("cannot list the shared folder");
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                String name = cur.getString(1);
                if (!validName(name)) continue;
                boolean isDir = Document.MIME_TYPE_DIR.equals(cur.getString(2));
                long size = cur.isNull(3) ? 0 : cur.getLong(3);
                long mtime = cur.isNull(4) ? 0 : cur.getLong(4);
                String rel = prefix + name;
                out.put(rel, new Remote(id, isDir, isDir ? 0 : size, mtime));
                if (isDir) {
                    dirs.add(new String[]{id, rel});
                } else {
                    total += size;
                }
            }
        } catch (SecurityException e) {
            throw new IOException("no access to the shared folder", e);
        }
        for (String[] d : dirs) {
            total += listTree(cr, tree, d[0], d[1] + "/", out);
            if (total > MAX_BYTES) break; // no need to keep walking
        }
        return total;
    }

    private static String findOrCreate(ContentResolver cr, Uri tree, String parentId,
                                       String name, boolean dir,
                                       Map<String, Map<String, String>> cache)
            throws IOException {
        Map<String, String> kids = cache.get(parentId);
        if (kids == null) {
            kids = new HashMap<>();
            Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            try (Cursor cur = cr.query(uri, new String[]{
                    Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                while (cur != null && cur.moveToNext()) kids.put(cur.getString(1), cur.getString(0));
            }
            cache.put(parentId, kids);
        }
        String id = kids.get(name);
        if (id != null) return id;
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId);
        Uri created = DocumentsContract.createDocument(cr, parent,
                dir ? Document.MIME_TYPE_DIR : "application/octet-stream", name);
        if (created == null) throw new IOException("cannot create " + name);
        id = DocumentsContract.getDocumentId(created);
        kids.put(name, id);
        return id;
    }

    static boolean validName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\0') < 0;
    }

    /** Every file and directory under {@code dir}, keyed by relative path ("a/b.txt"). */
    private static Map<String, File> localEntries(File dir) {
        Map<String, File> out = new TreeMap<>();
        walk(dir, "", out);
        return out;
    }

    private static void walk(File dir, String prefix, Map<String, File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String rel = prefix + f.getName();
            out.put(rel, f);
            if (f.isDirectory()) walk(f, rel + "/", out);
        }
    }

    /** Size and mtime of every regular file under {@code dir}. */
    static Map<String, Stamp> scan(File dir) {
        Map<String, Stamp> out = new TreeMap<>();
        for (Map.Entry<String, File> e : localEntries(dir).entrySet()) {
            File f = e.getValue();
            if (f.isFile()) out.put(e.getKey(), new Stamp(f.length(), f.lastModified()));
        }
        return out;
    }

    /** Files that are new in {@code after} or whose size or mtime changed. */
    static List<String> changed(Map<String, Stamp> before, Map<String, Stamp> after) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Stamp> e : after.entrySet()) {
            if (!e.getValue().same(before.get(e.getKey()))) out.add(e.getKey());
        }
        return out;
    }

    static void writeSnapshot(File f, Map<String, Stamp> files) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Stamp> e : files.entrySet()) {
            sb.append(e.getValue().size).append('\t').append(e.getValue().mtime).append('\t')
                    .append(e.getKey()).append('\n');
        }
        Io.writeText(f, sb.toString());
    }

    static Map<String, Stamp> readSnapshot(File f) throws IOException {
        Map<String, Stamp> out = new TreeMap<>();
        for (String line : Io.readText(f).split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", 3);
            if (p.length != 3) continue;
            try {
                out.put(p[2], new Stamp(Long.parseLong(p[0]), Long.parseLong(p[1])));
            } catch (NumberFormatException ignored) {
                // skip a damaged line
            }
        }
        return out;
    }
}
