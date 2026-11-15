package com.ccs.maple.model;

import android.content.Context;

import com.ccs.maple.util.Io;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Loads and saves VM definitions under filesDir/vms. */
public final class VmStore {
    private final File root;

    public VmStore(Context context) {
        root = new File(context.getFilesDir(), "vms");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
    }

    public File dir(String id) {
        File d = new File(root, id);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public File dir(VmConfig c) {
        return dir(c.id);
    }

    public synchronized List<VmConfig> list() {
        List<VmConfig> out = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                File f = new File(d, "config.json");
                if (!f.isFile()) continue;
                try {
                    out.add(VmConfig.fromJson(new JSONObject(Io.readText(f))));
                } catch (Exception ignored) {
                    // A corrupt definition is skipped rather than taking the list down.
                }
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public synchronized VmConfig get(String id) {
        if (id == null) return null;
        File f = new File(new File(root, id), "config.json");
        if (!f.isFile()) return null;
        try {
            return VmConfig.fromJson(new JSONObject(Io.readText(f)));
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void save(VmConfig c) {
        try {
            File d = dir(c.id);
            File tmp = new File(d, "config.json.tmp");
            Io.writeText(tmp, c.toJson().toString(2));
            if (!tmp.renameTo(new File(d, "config.json"))) {
                throw new java.io.IOException("rename failed");
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot save VM " + c.name, e);
        }
    }

    public synchronized void delete(String id) {
        Io.deleteRecursive(new File(root, id));
    }
}
