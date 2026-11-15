package com.ccs.maple.iso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ccs.maple.model.OsType;
import com.ccs.maple.model.VmConfig;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds small but structurally valid ISO 9660 images (primary volume descriptor, El Torito
 * boot record and catalog, /EFI/BOOT directory) and checks what {@link IsoInspector} reads.
 */
public class IsoInspectorTest {
    private static final int S = 2048;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void le32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
        // big-endian copy, as ISO 9660 requires
        b[off + 4] = (byte) (v >> 24);
        b[off + 5] = (byte) (v >> 16);
        b[off + 6] = (byte) (v >> 8);
        b[off + 7] = (byte) v;
    }

    private static void ascii(byte[] b, int off, String s) {
        byte[] x = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(x, 0, b, off, x.length);
    }

    /** Writes one directory record and returns its length. */
    private static int dirRecord(byte[] b, int off, int lba, int size, boolean dir, byte[] name) {
        int len = 33 + name.length;
        if (len % 2 == 1) len++;
        b[off] = (byte) len;
        le32(b, off + 2, lba);
        le32(b, off + 10, size);
        b[off + 25] = (byte) (dir ? 2 : 0);
        b[off + 32] = (byte) name.length;
        System.arraycopy(name, 0, b, off + 33, name.length);
        return len;
    }

    private static byte[] name(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * @param efiFiles  file names placed in /EFI/BOOT, e.g. "BOOTX64.EFI"
     * @param bios      default El Torito entry is BIOS-bootable
     * @param efi       an EFI El Torito entry is present
     */
    private static byte[] buildIso(String label, String[] efiFiles, boolean bios, boolean efi) {
        byte[] img = new byte[24 * S];

        // 16: primary volume descriptor
        int pvd = 16 * S;
        img[pvd] = 1;
        ascii(img, pvd + 1, "CD001");
        img[pvd + 6] = 1;
        String padded = (label + "                                ").substring(0, 32);
        ascii(img, pvd + 40, padded);
        dirRecord(img, pvd + 156, 20, S, true, new byte[]{0});

        // 17: El Torito boot record
        int br = 17 * S;
        img[br] = 0;
        ascii(img, br + 1, "CD001");
        img[br + 6] = 1;
        ascii(img, br + 7, "EL TORITO SPECIFICATION");
        le32(img, br + 0x47, 19);

        // 18: volume descriptor set terminator
        int term = 18 * S;
        img[term] = (byte) 255;
        ascii(img, term + 1, "CD001");
        img[term + 6] = 1;

        // 19: boot catalog
        int cat = 19 * S;
        img[cat] = 1;                       // validation entry
        img[cat + 1] = (byte) (efi && !bios ? 0xEF : 0);
        img[cat + 30] = 0x55;
        img[cat + 31] = (byte) 0xAA;
        img[cat + 32] = (byte) 0x88;        // default entry bootable
        if (efi && bios) {
            img[cat + 64] = (byte) 0x91;    // section header, final
            img[cat + 65] = (byte) 0xEF;    // EFI platform
            img[cat + 66] = 1;
            img[cat + 96] = (byte) 0x88;    // section entry bootable
        }

        // 20: root directory
        int root = 20 * S;
        dirRecord(img, root, 20, S, true, new byte[]{0});
        dirRecord(img, root + 34, 20, S, true, new byte[]{1});
        dirRecord(img, root + 68, 21, S, true, name("EFI"));

        // 21: /EFI
        int efiDir = 21 * S;
        dirRecord(img, efiDir, 21, S, true, new byte[]{0});
        dirRecord(img, efiDir + 34, 20, S, true, new byte[]{1});
        dirRecord(img, efiDir + 68, 22, S, true, name("BOOT"));

        // 22: /EFI/BOOT
        int boot = 22 * S;
        dirRecord(img, boot, 22, S, true, new byte[]{0});
        dirRecord(img, boot + 34, 21, S, true, new byte[]{1});
        int off = boot + 68;
        for (String f : efiFiles) {
            off += dirRecord(img, off, 23, 0, false, name(f + ";1"));
        }
        return img;
    }

    private File write(String fileName, byte[] iso) throws Exception {
        File f = tmp.newFile(fileName);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(iso);
        }
        return f;
    }

    @Test
    public void ubuntuLiveServerWithBiosAndUefi() throws Exception {
        byte[] iso = buildIso("UBUNTU-SERVER-2604", new String[]{"BOOTX64.EFI"}, true, true);
        IsoInspector.Info i = IsoInspector.inspect(write("ubuntu-26.04.1-live-server-amd64.iso", iso));
        assertTrue(i.isIso);
        assertEquals("UBUNTU-SERVER-2604", i.label);
        assertTrue(i.biosBoot);
        assertTrue(i.efiBoot);
        assertTrue(i.efiX64);
        assertEquals(VmConfig.ARCH_X86_64, i.arch);
        assertEquals(OsType.LINUX, i.os);
        assertEquals(VmConfig.FW_BIOS, i.firmware);
    }

    @Test
    public void arm64LinuxIsUefiAndAarch64() throws Exception {
        byte[] iso = buildIso("UBUNTU", new String[]{"BOOTAA64.EFI"}, false, true);
        IsoInspector.Info i = IsoInspector.inspect(write("ubuntu-26.04.1-live-server-arm64.iso", iso));
        assertTrue(i.efiArm64);
        assertFalse(i.biosBoot);
        assertEquals(VmConfig.ARCH_AARCH64, i.arch);
        assertEquals(VmConfig.FW_UEFI, i.firmware);
        assertEquals(OsType.LINUX, i.os);
    }

    @Test
    public void windows11FromNameAndLabel() throws Exception {
        byte[] iso = buildIso("CCCOMA_X64FRE_EN-US_DV9", new String[]{"BOOTX64.EFI"}, false, true);
        IsoInspector.Info i = IsoInspector.inspect(write("Win11_24H2_English_x64.iso", iso));
        assertEquals(OsType.WIN_11, i.os);
        assertEquals(VmConfig.ARCH_X86_64, i.arch);
        assertEquals(VmConfig.FW_UEFI, i.firmware);
        assertEquals(4096, i.ramMb);
    }

    @Test
    public void freeDosIsClassifiedByName() throws Exception {
        byte[] iso = buildIso("FD14LIVE", new String[0], true, false);
        IsoInspector.Info i = IsoInspector.inspect(write("FD14LIVE.iso", iso));
        assertEquals(OsType.DOS, i.os);
        assertEquals(VmConfig.FW_BIOS, i.firmware);
        assertEquals(64, i.ramMb);
        assertEquals(2, i.diskGb);
    }

    @Test
    public void reactOsIsClassifiedByName() throws Exception {
        byte[] iso = buildIso("REACTOS", new String[0], true, false);
        IsoInspector.Info i = IsoInspector.inspect(write("ReactOS-0.4.16-i386.iso", iso));
        assertEquals(OsType.REACTOS, i.os);
        assertEquals(VmConfig.ARCH_X86_64, i.arch);
    }

    @Test
    public void alpineArmIsDetectedWithoutBootFiles() throws Exception {
        // A file that is not an ISO at all: classification falls back to the name.
        File f = tmp.newFile("alpine-virt-3.24.4-aarch64.iso");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[64]);
        }
        IsoInspector.Info i = IsoInspector.inspect(f);
        assertFalse(i.isIso);
        assertEquals(VmConfig.ARCH_AARCH64, i.arch);
        assertEquals(VmConfig.FW_UEFI, i.firmware);
        assertEquals(OsType.LINUX, i.os);
    }

    @Test
    public void freeBsdArmFromName() throws Exception {
        File f = tmp.newFile("FreeBSD-15.1-RELEASE-arm64-aarch64-bootonly.iso");
        IsoInspector.Info i = IsoInspector.inspect(f);
        assertEquals(OsType.FREEBSD, i.os);
        assertEquals(VmConfig.ARCH_AARCH64, i.arch);
    }
}
