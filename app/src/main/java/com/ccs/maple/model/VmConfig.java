package com.ccs.maple.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Everything Maple knows about one virtual machine. Persisted as vms/&lt;id&gt;/config.json. */
public final class VmConfig {
    public static final String ARCH_X86_64 = "x86_64";
    public static final String ARCH_AARCH64 = "aarch64";

    public static final String FW_BIOS = "bios";
    public static final String FW_UEFI = "uefi";

    public static final String PROFILE_BALANCED = "balanced";
    public static final String PROFILE_MAX = "max";
    public static final String PROFILE_SUSTAINED = "sustained";

    public static final String SCALE_FIT = "fit";
    public static final String SCALE_STRETCH = "stretch";
    public static final String SCALE_INTEGER = "integer";

    public static final String ORIENT_AUTO = "auto";
    public static final String ORIENT_LANDSCAPE = "landscape";
    public static final String ORIENT_PORTRAIT = "portrait";

    public static final String INPUT_TRACKPAD = "trackpad";
    public static final String INPUT_TOUCH = "touch";
    public static final String INPUT_MOUSE = "mouse";

    public static final String SUSPEND_TAG = "maple-suspend";

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String arch = ARCH_X86_64;
    public OsType os = OsType.LINUX;
    public String firmware = FW_BIOS;
    public int ramMb = 1024;
    /** 0 = decided by the performance profile. */
    public int cpus = 0;

    /** File name of the primary disk inside the VM directory, or "" if none. */
    public String diskFile = "";
    /** content:// URI of an external primary disk (used instead of diskFile when set). */
    public String diskUri = "";
    /** Absolute path of an installer/CD image inside app storage, or "". */
    public String cdPath = "";
    /** content:// URI of an external CD image (used instead of cdPath when set). */
    public String cdUri = "";
    /** Display name of the CD image, for the UI only. */
    public String cdLabel = "";
    /** The CD image is a raw disk/USB image rather than an ISO 9660 CD. */
    public boolean cdIsDiskImage = false;
    public boolean bootCdFirst = false;

    public boolean network = true;
    /** Entries look like "tcp:2222:22" (protocol:host port:guest port). */
    public final List<String> portForwards = new ArrayList<>();
    public boolean sound = true;

    /** Absolute host directory exposed to the guest as a FAT USB drive, or "". */
    public String sharedFolder = "";
    public boolean sharedWritable = false;

    public String profile = PROFILE_BALANCED;
    public int displayWidth = 1024;
    public int displayHeight = 768;
    public String scaling = SCALE_FIT;
    public String orientation = ORIENT_AUTO;
    /** Pointer speed multiplier in percent (25..400). */
    public int pointerSpeed = 100;
    public String inputMode = INPUT_TRACKPAD;
    public boolean highRefresh = true;
    public boolean showHud = false;
    public boolean showKeys = true;

    public boolean win11Helper = false;
    public boolean suspendOnStop = false;
    /** A "maple-suspend" snapshot exists and should be resumed on next start. */
    public boolean hasSuspendState = false;

    public long created = System.currentTimeMillis();
    public long lastUsed = 0;

    public boolean isArm() {
        return ARCH_AARCH64.equals(arch);
    }

    public boolean isUefi() {
        return isArm() || FW_UEFI.equals(firmware);
    }

    public boolean hasDisk() {
        return !diskFile.isEmpty() || !diskUri.isEmpty();
    }

    public boolean hasCd() {
        return !cdPath.isEmpty() || !cdUri.isEmpty();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("arch", arch);
        o.put("os", os.name());
        o.put("firmware", firmware);
        o.put("ramMb", ramMb);
        o.put("cpus", cpus);
        o.put("diskFile", diskFile);
        o.put("diskUri", diskUri);
        o.put("cdPath", cdPath);
        o.put("cdUri", cdUri);
        o.put("cdLabel", cdLabel);
        o.put("cdIsDiskImage", cdIsDiskImage);
        o.put("bootCdFirst", bootCdFirst);
        o.put("network", network);
        JSONArray pf = new JSONArray();
        for (String s : portForwards) pf.put(s);
        o.put("portForwards", pf);
        o.put("sound", sound);
        o.put("sharedFolder", sharedFolder);
        o.put("sharedWritable", sharedWritable);
        o.put("profile", profile);
        o.put("displayWidth", displayWidth);
        o.put("displayHeight", displayHeight);
        o.put("scaling", scaling);
        o.put("orientation", orientation);
        o.put("pointerSpeed", pointerSpeed);
        o.put("inputMode", inputMode);
        o.put("highRefresh", highRefresh);
        o.put("showHud", showHud);
        o.put("showKeys", showKeys);
        o.put("win11Helper", win11Helper);
        o.put("suspendOnStop", suspendOnStop);
        o.put("hasSuspendState", hasSuspendState);
        o.put("created", created);
        o.put("lastUsed", lastUsed);
        return o;
    }

    public static VmConfig fromJson(JSONObject o) {
        VmConfig c = new VmConfig();
        c.id = o.optString("id", c.id);
        c.name = o.optString("name", "");
        c.arch = o.optString("arch", ARCH_X86_64);
        c.os = OsType.fromName(o.optString("os", "OTHER"));
        c.firmware = o.optString("firmware", FW_BIOS);
        c.ramMb = o.optInt("ramMb", 1024);
        c.cpus = o.optInt("cpus", 0);
        c.diskFile = o.optString("diskFile", "");
        c.diskUri = o.optString("diskUri", "");
        c.cdPath = o.optString("cdPath", "");
        c.cdUri = o.optString("cdUri", "");
        c.cdLabel = o.optString("cdLabel", "");
        c.cdIsDiskImage = o.optBoolean("cdIsDiskImage", false);
        c.bootCdFirst = o.optBoolean("bootCdFirst", false);
        c.network = o.optBoolean("network", true);
        JSONArray pf = o.optJSONArray("portForwards");
        if (pf != null) {
            for (int i = 0; i < pf.length(); i++) c.portForwards.add(pf.optString(i));
        }
        c.sound = o.optBoolean("sound", true);
        c.sharedFolder = o.optString("sharedFolder", "");
        c.sharedWritable = o.optBoolean("sharedWritable", false);
        c.profile = o.optString("profile", PROFILE_BALANCED);
        c.displayWidth = o.optInt("displayWidth", 1024);
        c.displayHeight = o.optInt("displayHeight", 768);
        c.scaling = o.optString("scaling", SCALE_FIT);
        c.orientation = o.optString("orientation", ORIENT_AUTO);
        c.pointerSpeed = o.optInt("pointerSpeed", 100);
        c.inputMode = o.optString("inputMode", INPUT_TRACKPAD);
        c.highRefresh = o.optBoolean("highRefresh", true);
        c.showHud = o.optBoolean("showHud", false);
        c.showKeys = o.optBoolean("showKeys", true);
        c.win11Helper = o.optBoolean("win11Helper", false);
        c.suspendOnStop = o.optBoolean("suspendOnStop", false);
        c.hasSuspendState = o.optBoolean("hasSuspendState", false);
        c.created = o.optLong("created", System.currentTimeMillis());
        c.lastUsed = o.optLong("lastUsed", 0);
        return c;
    }

    /** Parses "tcp:2222:22, udp:5000:5000" into validated entries; returns null on bad input. */
    public static List<String> parsePortForwards(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("[,;\\s]+")) {
            String s = raw.trim().toLowerCase();
            if (s.isEmpty()) continue;
            String[] p = s.split(":");
            if (p.length == 2) {
                p = new String[]{"tcp", p[0], p[1]};
            }
            if (p.length != 3) return null;
            if (!p[0].equals("tcp") && !p[0].equals("udp")) return null;
            try {
                int host = Integer.parseInt(p[1]);
                int guest = Integer.parseInt(p[2]);
                // Ports below 1024 cannot be bound by an unprivileged Android app.
                if (host < 1024 || host > 65535 || guest < 1 || guest > 65535) return null;
                out.add(p[0] + ":" + host + ":" + guest);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }
}
