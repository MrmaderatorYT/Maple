package com.ccs.maple.library;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.ccs.maple.model.OsType;
import com.ccs.maple.model.VmConfig;
import com.ccs.maple.model.VmStore;
import com.ccs.maple.qemu.QemuAssets;
import com.ccs.maple.util.Io;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads OS library images from their official mirrors, verifies the published checksum,
 * unpacks archives and creates a ready-to-boot VM.
 */
public final class Downloader {

    public enum Phase {DOWNLOADING, VERIFYING, UNPACKING, CREATING, DONE, FAILED, CANCELLED}

    public static final class Task {
        public final CatalogEntry entry;
        public volatile Phase phase = Phase.DOWNLOADING;
        public volatile long done;
        public volatile long total;
        public volatile String error;
        public volatile String vmId;
        volatile boolean cancel;

        Task(CatalogEntry e) {
            entry = e;
            total = e.sizeBytes;
        }

        public boolean active() {
            return phase != Phase.DONE && phase != Phase.FAILED && phase != Phase.CANCELLED;
        }
    }

    private static final Map<String, Task> TASKS = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastNotify;

    private Downloader() {
    }

    public static Task task(String entryId) {
        return TASKS.get(entryId);
    }

    public static void addListener(Runnable r) {
        LISTENERS.addIfAbsent(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void notifyListeners(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotify < 300) return;
        lastNotify = now;
        MAIN.post(() -> {
            for (Runnable r : LISTENERS) r.run();
        });
    }

    public static File libraryDir(Context c) {
        File d = new File(c.getFilesDir(), "library");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** The unpacked, verified image for an entry (may not exist yet). */
    public static File imageFile(Context c, CatalogEntry e) {
        String name = e.fileName;
        if (!"none".equals(e.compression)) {
            name = e.id + ("img".equals(e.media) ? ".img" : ".iso");
        }
        return new File(libraryDir(c), name);
    }

    public static void cancel(String entryId) {
        Task t = TASKS.get(entryId);
        if (t != null) t.cancel = true;
    }

    public static synchronized Task start(Context context, CatalogEntry entry) {
        Task existing = TASKS.get(entry.id);
        if (existing != null && existing.active()) return existing;
        Task t = new Task(entry);
        TASKS.put(entry.id, t);
        Context app = context.getApplicationContext();
        new Thread(() -> run(app, t), "download-" + entry.id).start();
        notifyListeners(true);
        return t;
    }

    private static void run(Context c, Task t) {
        CatalogEntry e = t.entry;
        try {
            File image = imageFile(c, e);
            if (!image.isFile()) {
                File part = new File(libraryDir(c), e.fileName + ".part");
                download(t, part);
                t.phase = Phase.VERIFYING;
                notifyListeners(true);
                String actual = digest(t, part, e.checksumAlgo);
                if (!actual.equalsIgnoreCase(e.checksumValue)) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    throw new IOException("Checksum mismatch.\nexpected " + e.checksumValue
                            + "\nactual   " + actual);
                }
                if ("none".equals(e.compression)) {
                    if (!part.renameTo(image)) throw new IOException("Cannot move image");
                } else {
                    t.phase = Phase.UNPACKING;
                    notifyListeners(true);
                    File tmp = new File(image.getPath() + ".tmp");
                    unpack(t, part, tmp);
                    if (!tmp.renameTo(image)) throw new IOException("Cannot move image");
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
            }
            t.phase = Phase.CREATING;
            notifyListeners(true);
            t.vmId = createVm(c, e, image).id;
            t.phase = Phase.DONE;
        } catch (CancelledException ce) {
            t.phase = Phase.CANCELLED;
        } catch (Exception ex) {
            t.error = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            t.phase = Phase.FAILED;
        }
        notifyListeners(true);
    }

    private static final class CancelledException extends IOException {
    }

    private static void download(Task t, File part) throws IOException {
        long have = part.isFile() ? part.length() : 0;
        if (t.entry.sizeBytes > 0 && have == t.entry.sizeBytes) {
            t.done = have;
            return;
        }
        if (t.entry.sizeBytes > 0 && have > t.entry.sizeBytes) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            have = 0;
        }
        URL url = new URL(t.entry.url);
        HttpURLConnection conn = null;
        // Follow redirects by hand so that mirror redirectors keep working across hosts.
        for (int hop = 0; hop < 8; hop++) {
            if (!"https".equals(url.getProtocol())) throw new IOException("Refusing non-HTTPS URL");
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "Maple-VM/1.0 (Android)");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new IOException("Redirect without location");
                url = new URL(url, loc);
                conn = null;
                continue;
            }
            break;
        }
        if (conn == null) throw new IOException("Too many redirects");
        try {
            int code = conn.getResponseCode();
            boolean append;
            if (code == 206) {
                append = true;
            } else if (code == 200) {
                append = false;
                have = 0;
            } else if (code == 416) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("Server rejected resume; try again");
            } else {
                throw new IOException("HTTP " + code + " from " + url.getHost());
            }
            long len = conn.getContentLengthLong();
            if (len > 0) t.total = have + len;
            long free = part.getParentFile().getUsableSpace();
            if (len > 0 && free < len + (64L << 20)) {
                throw new IOException("Not enough free storage ("
                        + Io.humanSize(len) + " needed)");
            }
            t.done = have;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (t.cancel) throw new CancelledException();
                    out.write(buf, 0, n);
                    t.done += n;
                    notifyListeners(false);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String digest(Task t, File f, String algo) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algo.toUpperCase(Locale.ROOT)
                    .replace("SHA", "SHA-").replace("SHA--", "SHA-"));
        } catch (Exception e) {
            throw new IOException("Unsupported checksum " + algo);
        }
        t.done = 0;
        t.total = f.length();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (t.cancel) throw new CancelledException();
                md.update(buf, 0, n);
                t.done += n;
                notifyListeners(false);
            }
        }
        return Io.hex(md.digest());
    }

    private static void unpack(Task t, File archive, File out) throws IOException {
        CatalogEntry e = t.entry;
        t.done = 0;
        t.total = 0;
        try (InputStream raw = new BufferedInputStream(new FileInputStream(archive), 1 << 16)) {
            InputStream in;
            switch (e.compression) {
                case "gz":
                    in = new GZIPInputStream(raw, 1 << 16);
                    break;
                case "xz":
                    in = new XZInputStream(raw);
                    break;
                case "zip": {
                    ZipInputStream zip = new ZipInputStream(raw);
                    ZipEntry ze;
                    boolean found = false;
                    while ((ze = zip.getNextEntry()) != null) {
                        if (!ze.isDirectory() && matches(ze.getName(), e.innerFile)) {
                            found = true;
                            t.total = Math.max(0, ze.getSize());
                            break;
                        }
                    }
                    if (!found) throw new IOException("Image not found inside archive");
                    in = zip;
                    break;
                }
                default:
                    throw new IOException("Unsupported compression " + e.compression);
            }
            try (OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (t.cancel) throw new CancelledException();
                    os.write(buf, 0, n);
                    t.done += n;
                    notifyListeners(false);
                }
            }
        }
    }

    private static boolean matches(String entryName, String pattern) {
        String base = entryName.substring(entryName.lastIndexOf('/') + 1);
        if (pattern == null || pattern.isEmpty()) {
            String l = base.toLowerCase(Locale.ROOT);
            return l.endsWith(".iso") || l.endsWith(".img");
        }
        if (pattern.startsWith("*")) {
            return base.toLowerCase(Locale.ROOT)
                    .endsWith(pattern.substring(1).toLowerCase(Locale.ROOT));
        }
        return base.equalsIgnoreCase(pattern) || entryName.equalsIgnoreCase(pattern);
    }

    /** Creates a VM that boots the downloaded image, with a blank disk to install to. */
    public static VmConfig createVm(Context c, CatalogEntry e, File image) throws IOException {
        VmStore store = new VmStore(c);
        VmConfig vm = new VmConfig();
        vm.name = e.name + (e.version.isEmpty() ? "" : " " + e.version)
                + (e.isArm() ? " (ARM64)" : "");
        vm.arch = e.isArm() ? VmConfig.ARCH_AARCH64 : VmConfig.ARCH_X86_64;
        vm.os = OsType.fromCatalog(e.os);
        vm.firmware = e.isArm() ? VmConfig.FW_UEFI : e.firmware;
        vm.ramMb = e.ramMb;
        vm.cdPath = image.getAbsolutePath();
        vm.cdLabel = e.title();
        vm.cdIsDiskImage = "img".equals(e.media);
        File dir = store.dir(vm);
        if (e.diskGb > 0) {
            vm.diskFile = "disk.qcow2";
            QemuAssets.createQcow2(c, new File(dir, vm.diskFile), e.diskGb);
        } else {
            vm.bootCdFirst = true;
        }
        store.save(vm);
        return vm;
    }
}
