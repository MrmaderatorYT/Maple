package com.ccs.maple.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Xml;

import com.ccs.maple.model.OsType;
import com.ccs.maple.model.VmConfig;
import com.ccs.maple.model.VmStore;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Imports a UTM virtual machine bundle (a ".utm" folder holding config.plist and the disk
 * images) picked through the system folder chooser.
 */
public final class UtmImporter {
    private UtmImporter() {
    }

    public static VmConfig importBundle(Context c, Uri tree) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Map<String, String[]> root = children(c, tree, rootId);
        String[] plist = root.get("config.plist");
        if (plist == null) throw new IOException("config.plist not found – pick the .utm folder");

        Object parsed;
        try (InputStream in = c.getContentResolver().openInputStream(
                DocumentsContract.buildDocumentUriUsingTree(tree, plist[0]))) {
            parsed = parsePlist(in);
        }
        if (!(parsed instanceof Map)) throw new IOException("Unsupported config.plist");
        Map<?, ?> cfg = (Map<?, ?>) parsed;
        Map<?, ?> system = map(cfg.get("System"));
        Map<?, ?> info = map(cfg.get("Information"));
        Map<?, ?> qemu = map(cfg.get("QEMU"));

        VmConfig vm = new VmConfig();
        String name = str(info.get("Name"), "");
        if (name.isEmpty()) name = str(cfg.get("Name"), "UTM VM");
        vm.name = name;
        String arch = str(system.get("Architecture"), "x86_64").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            vm.arch = VmConfig.ARCH_AARCH64;
        } else if (arch.equals("x86_64") || arch.equals("i386")) {
            vm.arch = VmConfig.ARCH_X86_64;
        } else {
            throw new IOException("Unsupported architecture: " + arch);
        }
        vm.ramMb = Math.max(64, Math.min(num(system.get("MemorySize"), 2048), 8192));
        vm.cpus = Math.max(0, Math.min(num(system.get("CPUCount"), 0), 8));
        boolean uefi = !(qemu.get("UEFIBoot") instanceof Boolean) || (Boolean) qemu.get("UEFIBoot");
        vm.firmware = vm.isArm() || uefi ? VmConfig.FW_UEFI : VmConfig.FW_BIOS;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("windows 11")) vm.os = OsType.WIN_11;
        else if (lower.contains("windows 10")) vm.os = OsType.WIN_10;
        else if (lower.contains("windows 7")) vm.os = OsType.WIN_7;
        else if (lower.contains("xp")) vm.os = OsType.WIN_XP;
        else if (lower.contains("windows")) vm.os = vm.isArm() ? OsType.WIN_11 : OsType.WIN_10;
        else if (lower.contains("bsd")) vm.os = OsType.FREEBSD;
        else vm.os = vm.isArm() ? OsType.LINUX : OsType.OTHER;

        // Disk images live in Data/ (UTM 4) or Images/ (UTM 3).
        Map<String, String[]> images = new HashMap<>();
        for (String dirName : new String[]{"Data", "Images"}) {
            String[] dir = root.get(dirName);
            if (dir != null) images.putAll(children(c, tree, dir[0]));
        }
        String diskName = null;
        Object drives = cfg.get("Drive");
        if (!(drives instanceof List)) drives = cfg.get("Drives");
        if (drives instanceof List) {
            for (Object o : (List<?>) drives) {
                Map<?, ?> d = map(o);
                String image = str(d.get("ImageName"), "");
                String type = str(d.get("ImageType"), "Disk");
                if (!image.isEmpty() && type.equalsIgnoreCase("Disk") && images.containsKey(image)) {
                    diskName = image;
                    break;
                }
            }
        }
        if (diskName == null) {
            for (String n : images.keySet()) {
                String l = n.toLowerCase(Locale.ROOT);
                if (l.endsWith(".qcow2") || l.endsWith(".img") || l.endsWith(".vmdk")
                        || l.endsWith(".vhdx") || l.endsWith(".vdi")) {
                    diskName = n;
                    break;
                }
            }
        }
        if (diskName == null) throw new IOException("No disk image found in the bundle");

        VmStore store = new VmStore(c);
        File dir = store.dir(vm);
        String ext = diskName.contains(".") ? diskName.substring(diskName.lastIndexOf('.')) : "";
        vm.diskFile = "disk" + ext.toLowerCase(Locale.ROOT);
        try (InputStream in = c.getContentResolver().openInputStream(
                DocumentsContract.buildDocumentUriUsingTree(tree, images.get(diskName)[0]));
             OutputStream out = new FileOutputStream(new File(dir, vm.diskFile))) {
            if (in == null) throw new IOException("Cannot read " + diskName);
            Io.copy(in, out);
        } catch (Exception e) {
            store.delete(vm.id);
            throw e;
        }
        store.save(vm);
        return vm;
    }

    /** Child documents of a directory: display name -> {document id, mime type}. */
    private static Map<String, String[]> children(Context c, Uri tree, String parentId) {
        Map<String, String[]> out = new LinkedHashMap<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                out.put(cur.getString(1), new String[]{cur.getString(0), cur.getString(2)});
            }
        }
        return out;
    }

    private static Map<?, ?> map(Object o) {
        return o instanceof Map ? (Map<?, ?>) o : new HashMap<>();
    }

    private static String str(Object o, String def) {
        return o instanceof String ? (String) o : def;
    }

    private static int num(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    // ------------------------------------------------------------------ XML property list

    private static Object parsePlist(InputStream in) throws Exception {
        if (in == null) throw new IOException("Cannot read config.plist");
        byte[] data = Io.readAll(in);
        if (data.length > 6 && new String(data, 0, 6).equals("bplist")) {
            throw new IOException("Binary property lists are not supported");
        }
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        p.setInput(new java.io.ByteArrayInputStream(data), null);
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && !p.getName().equals("plist")) {
                return readValue(p);
            }
            ev = p.next();
        }
        throw new IOException("Empty property list");
    }

    /** Parser is positioned on the START_TAG of a value; returns with it on the END_TAG. */
    private static Object readValue(XmlPullParser p) throws Exception {
        String tag = p.getName();
        switch (tag) {
            case "dict": {
                Map<String, Object> m = new LinkedHashMap<>();
                String key = null;
                while (p.next() != XmlPullParser.END_TAG) {
                    if (p.getEventType() != XmlPullParser.START_TAG) continue;
                    if (p.getName().equals("key")) {
                        key = p.nextText();
                    } else {
                        Object v = readValue(p);
                        if (key != null) m.put(key, v);
                        key = null;
                    }
                }
                return m;
            }
            case "array": {
                List<Object> l = new ArrayList<>();
                while (p.next() != XmlPullParser.END_TAG) {
                    if (p.getEventType() == XmlPullParser.START_TAG) l.add(readValue(p));
                }
                return l;
            }
            case "true":
                skip(p);
                return Boolean.TRUE;
            case "false":
                skip(p);
                return Boolean.FALSE;
            case "integer":
                try {
                    return Long.parseLong(p.nextText().trim());
                } catch (NumberFormatException e) {
                    return 0L;
                }
            case "real":
                try {
                    return Double.parseDouble(p.nextText().trim());
                } catch (NumberFormatException e) {
                    return 0d;
                }
            default: // string, date, data
                return p.nextText();
        }
    }

    private static void skip(XmlPullParser p) throws Exception {
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
        }
    }
}
