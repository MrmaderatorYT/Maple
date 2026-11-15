package com.ccs.maple.iso;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.ccs.maple.model.OsType;
import com.ccs.maple.model.VmConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads just enough of an installer image to choose a VM for it: the ISO 9660 volume label,
 * the El Torito boot catalog (BIOS and/or UEFI boot entries) and the names of the UEFI boot
 * loaders in /EFI/BOOT (which give the CPU architecture).
 */
public final class IsoInspector {
    private static final int SECTOR = 2048;

    public static final class Info {
        public boolean isIso;
        public String label = "";
        public boolean biosBoot;
        public boolean efiBoot;
        public boolean efiX64;
        public boolean efiArm64;
        public OsType os = OsType.OTHER;
        public String arch = VmConfig.ARCH_X86_64;
        public String firmware = VmConfig.FW_BIOS;
        public int ramMb = 1024;
        public int diskGb = 16;
    }

    private IsoInspector() {
    }

    public static Info inspect(Context c, Uri uri, String displayName) {
        try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return classify(new Info(), displayName);
            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                return inspect(in.getChannel(), displayName);
            }
        } catch (Exception e) {
            return classify(new Info(), displayName);
        }
    }

    public static Info inspect(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            return inspect(in.getChannel(), file.getName());
        } catch (Exception e) {
            return classify(new Info(), file.getName());
        }
    }

    private static Info inspect(FileChannel ch, String name) {
        Info info = new Info();
        try {
            readStructure(ch, info);
        } catch (Exception ignored) {
            // A damaged or unusual image still gets classified by its name.
        }
        return classify(info, name);
    }

    private static byte[] read(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos + buf.position());
            if (n <= 0) break;
        }
        if (buf.position() < len) throw new IOException("short read");
        return buf.array();
    }

    private static int le32(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static boolean isCd001(byte[] sector) {
        return sector[1] == 'C' && sector[2] == 'D' && sector[3] == '0' && sector[4] == '0'
                && sector[5] == '1';
    }

    private static void readStructure(FileChannel ch, Info info) throws IOException {
        long bootCatalog = -1;
        byte[] pvd = null;
        // Volume descriptors start at sector 16 and end with a type 255 terminator.
        for (int s = 16; s < 32; s++) {
            byte[] d = read(ch, (long) s * SECTOR, SECTOR);
            if (!isCd001(d)) break;
            int type = d[0] & 0xFF;
            if (type == 255) break;
            if (type == 1 && pvd == null) pvd = d;
            if (type == 0) {
                String sys = new String(d, 7, 23, StandardCharsets.US_ASCII);
                if (sys.equals("EL TORITO SPECIFICATION")) {
                    bootCatalog = le32(d, 0x47) & 0xFFFFFFFFL;
                }
            }
        }
        if (pvd == null) return;
        info.isIso = true;
        info.label = new String(pvd, 40, 32, StandardCharsets.US_ASCII).trim();

        if (bootCatalog > 0) readBootCatalog(ch, bootCatalog, info);

        // Root directory record lives at offset 156 of the primary volume descriptor.
        long rootLba = le32(pvd, 156 + 2) & 0xFFFFFFFFL;
        int rootSize = le32(pvd, 156 + 10);
        Entry efi = find(list(ch, rootLba, rootSize), "EFI");
        if (efi != null && efi.dir) {
            Entry boot = find(list(ch, efi.lba, efi.size), "BOOT");
            if (boot != null && boot.dir) {
                for (Entry e : list(ch, boot.lba, boot.size)) {
                    if (e.name.startsWith("BOOTX64")) info.efiX64 = true;
                    if (e.name.startsWith("BOOTAA64")) info.efiArm64 = true;
                }
            }
        }
    }

    private static void readBootCatalog(FileChannel ch, long lba, Info info) throws IOException {
        byte[] cat = read(ch, lba * SECTOR, SECTOR);
        if ((cat[0] & 0xFF) != 1) return; // validation entry
        int platform = cat[1] & 0xFF;
        boolean defaultBootable = (cat[32] & 0xFF) == 0x88;
        if (defaultBootable) {
            if (platform == 0xEF) info.efiBoot = true;
            else info.biosBoot = true;
        }
        int off = 64;
        while (off + 32 <= cat.length) {
            int id = cat[off] & 0xFF;
            if (id != 0x90 && id != 0x91) break;
            int secPlatform = cat[off + 1] & 0xFF;
            int count = (cat[off + 2] & 0xFF) | ((cat[off + 3] & 0xFF) << 8);
            off += 32;
            for (int i = 0; i < count && off + 32 <= cat.length; i++, off += 32) {
                if ((cat[off] & 0xFF) == 0x88) {
                    if (secPlatform == 0xEF) info.efiBoot = true;
                    else if (secPlatform == 0) info.biosBoot = true;
                }
            }
            if (id == 0x91) break;
        }
    }

    private static final class Entry {
        String name;
        long lba;
        int size;
        boolean dir;
    }

    private static Entry find(List<Entry> entries, String name) {
        for (Entry e : entries) {
            if (e.name.equals(name)) return e;
        }
        return null;
    }

    private static List<Entry> list(FileChannel ch, long lba, int size) throws IOException {
        List<Entry> out = new ArrayList<>();
        int len = Math.max(SECTOR, Math.min(size, 64 * 1024));
        byte[] d = read(ch, lba * SECTOR, len);
        int off = 0;
        while (off < d.length) {
            int recLen = d[off] & 0xFF;
            if (recLen == 0) {
                // Records never span sectors; skip the padding to the next one.
                off = (off / SECTOR + 1) * SECTOR;
                continue;
            }
            if (off + recLen > d.length || recLen < 34) break;
            int nameLen = d[off + 32] & 0xFF;
            if (nameLen > 0 && off + 33 + nameLen <= d.length) {
                Entry e = new Entry();
                e.lba = le32(d, off + 2) & 0xFFFFFFFFL;
                e.size = le32(d, off + 10);
                e.dir = (d[off + 25] & 2) != 0;
                String n = new String(d, off + 33, nameLen, StandardCharsets.US_ASCII);
                int semi = n.indexOf(';');
                if (semi >= 0) n = n.substring(0, semi);
                if (n.endsWith(".")) n = n.substring(0, n.length() - 1);
                e.name = n.toUpperCase(Locale.ROOT);
                if (nameLen > 1 || (d[off + 33] != 0 && d[off + 33] != 1)) out.add(e);
            }
            off += recLen;
        }
        return out;
    }

    // ------------------------------------------------------------------ classification

    private static boolean has(String s, String... needles) {
        for (String n : needles) {
            if (s.contains(n)) return true;
        }
        return false;
    }

    static Info classify(Info info, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String label = info.label.toLowerCase(Locale.ROOT);
        String all = name + " " + label;

        // Architecture: UEFI boot loaders are authoritative, names are the fallback.
        boolean arm;
        if (info.efiArm64 && !info.efiX64) {
            arm = true;
        } else if (info.efiX64) {
            arm = false;
        } else {
            arm = has(all, "arm64", "aarch64", "_a64", "a64fre", "-arm-", "woa");
        }
        info.arch = arm ? VmConfig.ARCH_AARCH64 : VmConfig.ARCH_X86_64;

        boolean winLabel = label.startsWith("cccoma_") || label.startsWith("ccsa_")
                || label.startsWith("cpba_") || label.startsWith("j_ccsa") || label.contains("fre_")
                || label.startsWith("esd-iso") || label.startsWith("sss_")
                || label.startsWith("grmc") || label.startsWith("gsp1rm")
                || label.startsWith("grtm") || label.startsWith("wxp") || label.startsWith("wxh")
                || label.startsWith("vrm") || label.startsWith("irm_") || label.startsWith("hb1_");
        if (winLabel || has(name, "windows", "win1", "win7", "win8", "winxp", "win_", "win-",
                "tiny10", "tiny11", "ltsc")) {
            if (has(all, "xp", "grtm", "wxp", "wxh", "vrm", "2003", "win2k", "2000")) {
                info.os = OsType.WIN_XP;
            } else if (has(all, "win7", "windows_7", "windows 7", "windows7", "grmc", "gsp1rm",
                    "7600", "7601", "vista", "2008")) {
                info.os = OsType.WIN_7;
            } else if (has(name, "11", "24h2", "25h2", "23h2", "22h2_11") || arm) {
                info.os = OsType.WIN_11;
            } else {
                info.os = OsType.WIN_10;
            }
        } else if (has(all, "android", "bliss", "lineage", "phoenixos", "primeos", "remixos")) {
            info.os = OsType.ANDROID;
        } else if (has(all, "freebsd", "ghostbsd", "dragonfly", "midnightbsd")) {
            info.os = OsType.FREEBSD;
        } else if (has(all, "openbsd") || name.matches("install\\d\\d\\.(iso|img)")) {
            info.os = OsType.OPENBSD;
        } else if (has(all, "netbsd")) {
            info.os = OsType.NETBSD;
        } else if (has(all, "haiku")) {
            info.os = OsType.HAIKU;
        } else if (has(all, "reactos")) {
            info.os = OsType.REACTOS;
        } else if (has(all, "freedos", "fd13", "fd14", "fd12", "msdos", "ms-dos", "dos6", "dos7",
                "drdos")) {
            info.os = OsType.DOS;
        } else if (has(all, "linux", "ubuntu", "debian", "fedora", "alpine", "arch", "mint",
                "kali", "suse", "centos", "rocky", "alma", "void", "manjaro", "nixos", "gentoo",
                "slack", "tinycore", "puppy", "pop-os", "pop_os", "zorin", "elementary", "mx-",
                "rhel", "oracle", "devuan", "antix", "endeavour", "tails", "slax", "knoppix",
                "gparted", "clonezilla", "systemrescue", "live")) {
            info.os = OsType.LINUX;
        } else {
            info.os = OsType.OTHER;
        }

        if (arm) {
            info.firmware = VmConfig.FW_UEFI;
        } else if (info.isIso && !info.biosBoot && info.efiBoot) {
            info.firmware = VmConfig.FW_UEFI;
        } else {
            info.firmware = VmConfig.FW_BIOS;
        }

        switch (info.os) {
            case DOS:
                info.ramMb = 64;
                info.diskGb = 2;
                break;
            case WIN_XP:
                info.ramMb = 512;
                info.diskGb = 10;
                break;
            case REACTOS:
                info.ramMb = 512;
                info.diskGb = 8;
                break;
            case WIN_7:
                info.ramMb = 2048;
                info.diskGb = 32;
                break;
            case WIN_10:
                info.ramMb = 3072;
                info.diskGb = 40;
                break;
            case WIN_11:
                info.ramMb = 4096;
                info.diskGb = 64;
                break;
            case ANDROID:
                info.ramMb = 2048;
                info.diskGb = 16;
                break;
            case HAIKU:
                info.ramMb = 1024;
                info.diskGb = 8;
                break;
            default:
                info.ramMb = 1024;
                info.diskGb = 16;
        }
        return info;
    }
}
