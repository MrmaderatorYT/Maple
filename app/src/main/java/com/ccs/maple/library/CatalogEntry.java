package com.ccs.maple.library;

import android.content.Context;

import com.ccs.maple.util.Io;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One downloadable operating system image from assets/os_catalog.json. */
public final class CatalogEntry {
    public String id;
    public String name;
    public String version;
    public String variant;
    public String arch;
    public String os;
    public String media;
    public String url;
    public String fileName;
    public long sizeBytes;
    public String compression;
    public String innerFile;
    public String checksumAlgo;
    public String checksumValue;
    public String firmware;
    public int ramMb;
    public int diskGb;
    public String homepage;
    public String description;

    public String title() {
        StringBuilder sb = new StringBuilder(name);
        if (!version.isEmpty()) sb.append(' ').append(version);
        if (!variant.isEmpty()) sb.append(" · ").append(variant);
        return sb.toString();
    }

    public boolean isArm() {
        return "aarch64".equals(arch);
    }

    private static List<CatalogEntry> cache;

    public static synchronized List<CatalogEntry> load(Context c) {
        if (cache != null) return cache;
        List<CatalogEntry> out = new ArrayList<>();
        try (InputStream in = c.getAssets().open("os_catalog.json")) {
            JSONObject root = new JSONObject(new String(Io.readAll(in), StandardCharsets.UTF_8));
            JSONArray arr = root.getJSONArray("entries");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                CatalogEntry e = new CatalogEntry();
                e.id = o.getString("id");
                e.name = o.getString("name");
                e.version = o.optString("version", "");
                e.variant = o.optString("variant", "");
                e.arch = o.optString("arch", "x86_64");
                e.os = o.optString("os", "linux");
                e.media = o.optString("media", "iso");
                e.url = o.getString("url");
                e.fileName = o.getString("fileName");
                e.sizeBytes = o.optLong("sizeBytes", 0);
                e.compression = o.optString("compression", "none");
                e.innerFile = o.optString("innerFile", "");
                JSONObject sum = o.getJSONObject("checksum");
                e.checksumAlgo = sum.getString("algo");
                e.checksumValue = sum.getString("value").toLowerCase();
                e.firmware = o.optString("firmware", "bios");
                e.ramMb = o.optInt("ramMb", 1024);
                e.diskGb = o.optInt("diskGb", 8);
                e.homepage = o.optString("homepage", "");
                e.description = o.optString("description", "");
                out.add(e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Bad OS catalog", e);
        }
        cache = out;
        return out;
    }
}
