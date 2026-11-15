package com.ccs.maple.qemu;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;

import com.ccs.maple.util.Io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Locates the bundled QEMU binaries and unpacks the firmware directory used with -L. */
public final class QemuAssets {
    private static final String ASSET_DIR = "qemu";
    private static final Object LOCK = new Object();

    private QemuAssets() {
    }

    public static File shareDir(Context c) {
        return new File(c.getFilesDir(), "qemu-share");
    }

    public static File systemBinary(Context c, boolean arm) {
        return new File(c.getApplicationInfo().nativeLibraryDir,
                arm ? "libqemu-system-aarch64.so" : "libqemu-system-x86_64.so");
    }

    public static File imgBinary(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libqemu-img.so");
    }

    public static boolean binariesPresent(Context c) {
        return systemBinary(c, false).isFile() && systemBinary(c, true).isFile()
                && imgBinary(c).isFile();
    }

    /** Extracts assets/qemu to filesDir/qemu-share once per installed app build. */
    public static File ensureShare(Context c) throws IOException {
        synchronized (LOCK) {
            File dir = shareDir(c);
            File stamp = new File(dir, ".stamp");
            String want = buildStamp(c);
            if (stamp.isFile() && want.equals(Io.readText(stamp).trim())) return dir;
            Io.deleteRecursive(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("mkdir " + dir);
            copyAssetDir(c.getAssets(), ASSET_DIR, dir);
            Io.writeText(stamp, want);
            return dir;
        }
    }

    private static String buildStamp(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.getLongVersionCode() + ":" + pi.lastUpdateTime;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void copyAssetDir(AssetManager am, String path, File out) throws IOException {
        String[] names = am.list(path);
        if (names == null) return;
        for (String name : names) {
            String child = path + "/" + name;
            String[] sub = am.list(child);
            if (sub != null && sub.length > 0) {
                File d = new File(out, name);
                if (!d.mkdirs() && !d.isDirectory()) throw new IOException("mkdir " + d);
                copyAssetDir(am, child, d);
            } else {
                try (InputStream in = am.open(child);
                     OutputStream os = new FileOutputStream(new File(out, name))) {
                    Io.copy(in, os);
                }
            }
        }
    }

    /** Runs qemu-img and returns its combined output; throws if it exits non-zero. */
    public static String qemuImg(Context c, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(imgBinary(c).getAbsolutePath());
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        File tmp = new File(c.getCacheDir(), "tmp");
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        pb.environment().put("TMPDIR", tmp.getAbsolutePath());
        pb.environment().put("HOME", c.getFilesDir().getAbsolutePath());
        Process p = pb.start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(Io.readAll(in));
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            p.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (code != 0) throw new IOException("qemu-img failed (" + code + "): " + out.trim());
        return out;
    }

    public static void createQcow2(Context c, File file, int sizeGb) throws IOException {
        qemuImg(c, "create", "-f", "qcow2", file.getAbsolutePath(), sizeGb + "G");
    }
}
