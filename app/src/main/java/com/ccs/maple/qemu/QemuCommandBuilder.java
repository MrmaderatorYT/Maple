package com.ccs.maple.qemu;

import android.app.ActivityManager;
import android.content.Context;
import android.database.Cursor;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Display;

import com.ccs.maple.R;
import com.ccs.maple.model.OsType;
import com.ccs.maple.model.VmConfig;
import com.ccs.maple.util.Io;
import com.ccs.maple.util.SharedFolder;

import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a {@link VmConfig} into a QEMU command line. Each guest OS gets virtual hardware it
 * ships drivers for (disk bus, network card, sound card, graphics, pointing device).
 */
public final class QemuCommandBuilder {

    public static final class Result {
        public String exe;
        public final List<String> argv = new ArrayList<>();
        public final List<String> env = new ArrayList<>();
        /** Descriptors that must survive into the QEMU process; closed by the caller after spawn. */
        public final List<ParcelFileDescriptor> fds = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public String vncSocket;
        public String qmpSocket;
        public int cpus;
        public boolean relativePointer;
        public boolean resumesSavedState;
    }

    private final Context ctx;
    private final VmConfig vm;
    private final File vmDir;
    private final Result r = new Result();
    private int nextFdSet = 1;

    // Hardware decisions
    private boolean q35;
    private String usbBus;        // bus for HID devices
    private String storageUsbBus; // high-speed bus for usb-storage

    public QemuCommandBuilder(Context ctx, VmConfig vm, File vmDir) {
        this.ctx = ctx.getApplicationContext();
        this.vm = vm;
        this.vmDir = vmDir;
    }

    public static File runDir(Context c) {
        // Kept short: unix socket paths are limited to 107 bytes.
        File d = new File(c.getCacheDir(), "r");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static int cpuCount(VmConfig vm) {
        int total = Runtime.getRuntime().availableProcessors();
        int n;
        if (vm.cpus > 0) {
            n = Math.min(vm.cpus, Math.max(1, total));
        } else {
            switch (vm.profile) {
                case VmConfig.PROFILE_MAX:
                    n = clamp(total - 1, 1, 8);
                    break;
                case VmConfig.PROFILE_SUSTAINED:
                    n = clamp(total / 4, 1, 2);
                    break;
                default:
                    n = clamp(total / 2, 1, 4);
            }
        }
        if (vm.os == OsType.DOS) n = 1;
        if (vm.os == OsType.WIN_XP || vm.os == OsType.REACTOS) n = Math.min(n, 2);
        return n;
    }

    public Result build() throws IOException {
        boolean arm = vm.isArm();
        File share = QemuAssets.ensureShare(ctx);
        File exe = QemuAssets.systemBinary(ctx, arm);
        if (!exe.isFile()) throw new IOException("QEMU binary missing: " + exe.getName());
        r.exe = exe.getAbsolutePath();

        File run = runDir(ctx);
        String shortId = vm.id.replace("-", "").substring(0, 10);
        r.vncSocket = new File(run, shortId + ".v").getAbsolutePath();
        r.qmpSocket = new File(run, shortId + ".q").getAbsolutePath();
        //noinspection ResultOfMethodCallIgnored
        new File(r.vncSocket).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(r.qmpSocket).delete();

        File tmp = new File(ctx.getCacheDir(), "tmp");
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        r.env.add("TMPDIR=" + tmp.getAbsolutePath());
        r.env.add("HOME=" + ctx.getFilesDir().getAbsolutePath());
        r.env.add("PATH=/system/bin");
        r.env.add("MAPLE_VNC_REFRESH_MS=" + refreshIntervalMs());
        // Upstream backs off to 3 s between refreshes when the screen is idle, which makes the
        // first frames after a pause lag; keep the back-off short.
        r.env.add("MAPLE_VNC_REFRESH_MAX_MS=80");
        File resolv = writeResolvConf(run);
        if (resolv != null) r.env.add("SLIRP_RESOLV_CONF=" + resolv.getAbsolutePath());

        r.cpus = cpuCount(vm);

        List<String> a = r.argv;
        a.add(exe.getName());
        add("-name", sanitizeName(vm.name));
        add("-L", share.getAbsolutePath());
        a.add("-nodefaults");
        a.add("-no-user-config");
        add("-display", "none");
        add("-monitor", "none");
        add("-qmp", "unix:" + r.qmpSocket + ",server=on,wait=off");
        add("-audiodev", "none,id=snd0");
        add("-vnc", "unix:" + r.vncSocket + ",audiodev=snd0");

        machine(arm);
        add("-accel", "tcg,thread=multi,tb-size=" + tbSizeMb());
        add("-smp", String.valueOf(r.cpus));
        add("-m", String.valueOf(vm.ramMb));
        if (vm.os.usesLocalTime()) add("-rtc", "base=localtime");

        firmware(arm, share);
        usb(arm);
        display(arm);
        input(arm);
        storage(arm);
        network(arm);
        sound();
        extras(arm);

        if (vm.hasSuspendState) {
            add("-loadvm", VmConfig.SUSPEND_TAG);
            r.resumesSavedState = true;
        }
        return r;
    }

    // ---------------------------------------------------------------- machine / cpu

    private void machine(boolean arm) {
        if (arm) {
            add("-machine", "virt,gic-version=3");
            // "max" carries the ARMv8.x features Windows 11 on ARM insists on; the fixed
            // Cortex-A76 model is cheaper to emulate for everything else.
            add("-cpu", vm.os.isWindows() ? "max" : "cortex-a76");
            return;
        }
        switch (vm.os) {
            case DOS:
                q35 = false;
                add("-cpu", "pentium3");
                break;
            case WIN_XP:
            case REACTOS:
                q35 = false;
                add("-cpu", "core2duo");
                break;
            case WIN_7:
                q35 = false;
                add("-cpu", "core2duo");
                break;
            case OTHER:
                q35 = false;
                add("-cpu", "max");
                break;
            default:
                q35 = true;
                add("-cpu", "max");
        }
        add("-machine", q35 ? "q35" : "pc");
    }

    private int tbSizeMb() {
        int want;
        switch (vm.profile) {
            case VmConfig.PROFILE_MAX:
                want = 512;
                break;
            case VmConfig.PROFILE_SUSTAINED:
                want = 128;
                break;
            default:
                want = 256;
        }
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long hostMb = mi.totalMem / (1024 * 1024);
        return (int) clamp(Math.min(want, hostMb / 12), 64, 1024);
    }

    private int refreshIntervalMs() {
        float hz = 60f;
        if (vm.highRefresh) {
            try {
                DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
                Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                for (Display.Mode m : d.getSupportedModes()) hz = Math.max(hz, m.getRefreshRate());
            } catch (Exception ignored) {
                // keep 60
            }
            hz = Math.min(hz, 144f);
        }
        return clamp(Math.round(1000f / hz), 6, 33);
    }

    // ---------------------------------------------------------------- firmware

    private void firmware(boolean arm, File share) throws IOException {
        if (!vm.isUefi()) return;
        String code = arm ? "edk2-aarch64-code.fd" : "edk2-x86_64-code.fd";
        String varsTemplate = arm ? "edk2-arm-vars.fd" : "edk2-i386-vars.fd";
        File codeFile = new File(share, code);
        if (!codeFile.isFile()) throw new IOException("UEFI firmware missing: " + code);
        // The variable store is kept as qcow2 so that it can take part in saved states.
        File vars = new File(vmDir, "efivars.qcow2");
        if (!vars.isFile()) {
            QemuAssets.qemuImg(ctx, "convert", "-f", "raw", "-O", "qcow2",
                    new File(share, varsTemplate).getAbsolutePath(), vars.getAbsolutePath());
        }
        add("-drive", "if=pflash,unit=0,format=raw,readonly=on,file=" + esc(codeFile.getPath()));
        add("-drive", "if=pflash,unit=1,format=qcow2,file=" + esc(vars.getPath()));
    }

    // ---------------------------------------------------------------- usb / input / display

    private boolean legacyUsb() {
        if (vm.isArm()) return false;
        switch (vm.os) {
            case DOS:
            case WIN_XP:
            case WIN_7:
            case REACTOS:
            case OTHER:
                return true;
            default:
                return false;
        }
    }

    private void usb(boolean arm) {
        if (legacyUsb()) {
            // Windows XP/7 have no xHCI driver: UHCI for the tablet, EHCI for mass storage.
            add("-device", "piix3-usb-uhci,id=usb");
            add("-device", "usb-ehci,id=ehci");
            usbBus = "usb.0";
            storageUsbBus = "ehci.0";
        } else {
            add("-device", "qemu-xhci,id=usb");
            usbBus = "usb.0";
            storageUsbBus = "usb.0";
        }
    }

    private void display(boolean arm) {
        String res = ",xres=" + vm.displayWidth + ",yres=" + vm.displayHeight;
        if (arm) {
            if (vm.os.isWindows()) {
                // Windows on ARM has no virtio-gpu driver; it draws on the UEFI framebuffer.
                add("-device", "ramfb");
            } else {
                add("-device", "virtio-gpu-pci" + res);
            }
            return;
        }
        switch (vm.os) {
            case DOS:
            case WIN_XP:
                // Both ship a Cirrus Logic GD5446 driver.
                add("-device", "cirrus-vga");
                break;
            default:
                add("-device", "VGA,vgamem_mb=32" + res);
        }
    }

    private void input(boolean arm) {
        if (arm) {
            add("-device", "usb-kbd,bus=" + usbBus);
            add("-device", "usb-tablet,bus=" + usbBus);
            return;
        }
        // x86 machines always have a PS/2 keyboard and mouse. A USB tablet adds absolute
        // pointing, which DOS and ReactOS cannot use.
        if (vm.os == OsType.DOS || vm.os == OsType.REACTOS) {
            r.relativePointer = true;
        } else {
            add("-device", "usb-tablet,bus=" + usbBus);
        }
    }

    // ---------------------------------------------------------------- storage

    private boolean virtioDisk() {
        switch (vm.os) {
            case LINUX:
            case ANDROID:
            case FREEBSD:
            case OPENBSD:
            case NETBSD:
                return true;
            default:
                return false;
        }
    }

    private void storage(boolean arm) throws IOException {
        boolean cdFirst = vm.bootCdFirst && vm.hasCd();
        int hdBoot = cdFirst ? 2 : 1;
        int cdBoot = cdFirst ? 1 : 2;

        if (vm.hasDisk()) {
            String spec;
            if (!vm.diskUri.isEmpty()) {
                Uri uri = Uri.parse(vm.diskUri);
                String fmt = formatForName(displayName(ctx, uri));
                spec = "if=none,id=hd0,file=" + fdSet(uri, true) + ",file.locking=off"
                        + (fmt != null ? ",format=" + fmt : "");
            } else {
                File f = new File(vmDir, vm.diskFile);
                if (!f.isFile()) throw new IOException("Disk image missing: " + f.getName());
                String fmt = formatForName(f.getName());
                spec = "if=none,id=hd0,file=" + esc(f.getAbsolutePath())
                        + (fmt != null ? ",format=" + fmt : "");
            }
            add("-drive", spec + ",discard=unmap,detect-zeroes=unmap");
            if (arm) {
                if (vm.os.isWindows()) {
                    add("-device", "nvme,drive=hd0,serial=maple0,bootindex=" + hdBoot);
                } else {
                    add("-device", "virtio-blk-pci,drive=hd0,bootindex=" + hdBoot);
                }
            } else if (virtioDisk()) {
                add("-device", "virtio-blk-pci,drive=hd0,bootindex=" + hdBoot);
            } else {
                add("-device", "ide-hd,drive=hd0,bus=ide.0,bootindex=" + hdBoot);
            }
        }

        if (vm.hasCd()) {
            String file;
            String extra = "";
            if (!vm.cdUri.isEmpty()) {
                file = fdSet(Uri.parse(vm.cdUri), false);
                extra = ",file.locking=off";
            } else {
                File f = new File(vm.cdPath);
                if (!f.isFile()) throw new IOException("Installer image missing: " + f.getName());
                file = esc(f.getAbsolutePath());
            }
            if (vm.cdIsDiskImage) {
                add("-drive", "if=none,id=cd0,format=raw,read-only=on,file=" + file + extra);
                add("-device", "usb-storage,drive=cd0,bus=" + storageUsbBus + ",bootindex="
                        + cdBoot);
            } else if (arm) {
                // The virt machine has no IDE; a USB CD-ROM is understood by every guest's
                // installer and by the UEFI firmware.
                add("-drive", "if=none,id=cd0,media=cdrom,format=raw,read-only=on,file=" + file
                        + extra);
                add("-device", "usb-storage,drive=cd0,removable=on,bus=" + storageUsbBus
                        + ",bootindex=" + cdBoot);
            } else {
                add("-drive", "if=none,id=cd0,media=cdrom,format=raw,read-only=on,file=" + file
                        + extra);
                add("-device", "ide-cd,drive=cd0,bus=ide.1,bootindex=" + cdBoot);
            }
        }
    }

    /** Hands a Storage Access Framework document to QEMU as /dev/fdset/N. */
    private String fdSet(Uri uri, boolean writable) throws IOException {
        int set = nextFdSet++;
        ParcelFileDescriptor ro;
        try {
            ro = ctx.getContentResolver().openFileDescriptor(uri, "r");
        } catch (Exception e) {
            throw new IOException("Cannot open " + uri + ": " + e.getMessage());
        }
        if (ro == null) throw new IOException("Cannot open " + uri);
        r.fds.add(ro);
        add("-add-fd", "fd=" + ro.getFd() + ",set=" + set);
        if (writable) {
            try {
                ParcelFileDescriptor rw = ctx.getContentResolver().openFileDescriptor(uri, "rw");
                if (rw != null) {
                    r.fds.add(rw);
                    add("-add-fd", "fd=" + rw.getFd() + ",set=" + set);
                }
            } catch (Exception e) {
                r.warnings.add("Disk is read-only: " + e.getMessage());
            }
        }
        return "/dev/fdset/" + set;
    }

    // ---------------------------------------------------------------- network / sound

    private String nicModel(boolean arm) {
        if (arm) return vm.os.isWindows() ? "usb-net" : "virtio-net-pci";
        switch (vm.os) {
            case DOS:
                return "pcnet";
            case WIN_XP:
            case REACTOS:
                return "rtl8139";
            case WIN_10:
            case WIN_11:
                return "e1000e";
            case LINUX:
            case ANDROID:
            case FREEBSD:
            case OPENBSD:
            case NETBSD:
                return "virtio-net-pci";
            default:
                return "e1000";
        }
    }

    private void network(boolean arm) {
        if (!vm.network) return;
        StringBuilder net = new StringBuilder("user,id=net0");
        List<String> fwd = new ArrayList<>(vm.portForwards);
        if (vm.os == OsType.ANDROID) {
            // Keep adb reachable for Android guests unless the user mapped port 5555 already.
            boolean has = false;
            for (String f : fwd) {
                if (f.endsWith(":5555")) has = true;
            }
            if (!has) {
                int port = firstFreeTcp(5555, 5585);
                if (port > 0) fwd.add("tcp:" + port + ":5555");
            }
        }
        for (String f : fwd) {
            String[] p = f.split(":");
            if (p.length != 3) continue;
            int host = Integer.parseInt(p[1]);
            if (!portFree(p[0], host)) {
                r.warnings.add("Port " + host + " is busy; forward " + f + " skipped");
                continue;
            }
            net.append(",hostfwd=").append(p[0]).append("::").append(host).append("-:")
                    .append(p[2]);
        }
        add("-netdev", net.toString());
        String model = nicModel(arm);
        if (model.equals("usb-net")) {
            add("-device", "usb-net,netdev=net0,id=nic0,bus=" + usbBus);
        } else {
            add("-device", model + ",netdev=net0,id=nic0");
        }
    }

    private void sound() {
        if (!vm.sound) return;
        if (vm.isArm()) {
            // The ISA and legacy PCI sound cards only exist on PC machines.
            add("-device", "intel-hda");
            add("-device", "hda-output,audiodev=snd0");
            return;
        }
        switch (vm.os) {
            case DOS:
                add("-device", "sb16,audiodev=snd0");
                break;
            case WIN_XP:
            case REACTOS:
                add("-device", "AC97,audiodev=snd0");
                break;
            default:
                add("-device", "intel-hda");
                add("-device", "hda-output,audiodev=snd0");
        }
    }

    // ---------------------------------------------------------------- shared folder, helpers

    private void extras(boolean arm) throws IOException {
        if (!vm.sharedUri.isEmpty() && vm.os != OsType.DOS) {
            // VmSession mirrors the picked folder into this private directory before start.
            File dir = SharedFolder.stagingDir(vmDir);
            if (dir.isDirectory()) {
                String fat = vm.sharedWritable ? "fat:rw:" : "fat:";
                add("-drive", "if=none,id=share0,format=raw"
                        + (vm.sharedWritable ? "" : ",read-only=on")
                        + ",file=" + fat + esc(dir.getAbsolutePath()));
                add("-device", "usb-storage,drive=share0,removable=on,bus=" + storageUsbBus);
            } else {
                r.warnings.add(ctx.getString(R.string.share_unavailable));
            }
        }
        if (vm.win11Helper && vm.os == OsType.WIN_11) {
            File dir = new File(vmDir, "win11-helper");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            Io.writeText(new File(dir, "autounattend.xml"), Win11Helper.autounattend(arm));
            add("-drive", "if=none,id=helper0,format=raw,read-only=on,file=fat:"
                    + esc(dir.getAbsolutePath()));
            add("-device", "usb-storage,drive=helper0,removable=on,bus=" + storageUsbBus);
        }
    }

    /**
     * Android has no /etc/resolv.conf. The guest's DNS requests are relayed to the servers of
     * the phone's active network, written in resolv.conf form for the bundled libslirp.
     */
    private File writeResolvConf(File dir) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network net = cm.getActiveNetwork();
            android.net.LinkProperties lp = net != null ? cm.getLinkProperties(net) : null;
            if (lp == null || lp.getDnsServers().isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (java.net.InetAddress a : lp.getDnsServers()) {
                String host = a.getHostAddress();
                if (host == null) continue;
                int scope = host.indexOf('%');
                if (scope >= 0) host = host.substring(0, scope);
                sb.append("nameserver ").append(host).append('\n');
            }
            File f = new File(dir, "resolv.conf");
            Io.writeText(f, sb.toString());
            return f;
        } catch (Exception e) {
            return null; // libslirp falls back to a public resolver
        }
    }

    // ---------------------------------------------------------------- utilities

    private void add(String opt, String value) {
        r.argv.add(opt);
        r.argv.add(value);
    }

    /** QEMU option parser: a literal comma is written as two commas. */
    private static String esc(String s) {
        return s.replace(",", ",,");
    }

    private static String sanitizeName(String s) {
        String out = s.replaceAll("[^\\p{L}\\p{N} ._-]", "_").trim();
        return out.isEmpty() ? "vm" : out;
    }

    private static int clamp(long v, int lo, int hi) {
        return (int) Math.max(lo, Math.min(hi, v));
    }

    /** QEMU block driver for a file name, or null to let QEMU probe the image. */
    public static String formatForName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".qcow2") || n.endsWith(".qcow")) return "qcow2";
        if (n.endsWith(".img") || n.endsWith(".raw") || n.endsWith(".iso")) return "raw";
        if (n.endsWith(".vhdx")) return "vhdx";
        if (n.endsWith(".vhd") || n.endsWith(".vpc")) return "vpc";
        if (n.endsWith(".vmdk")) return "vmdk";
        if (n.endsWith(".vdi")) return "vdi";
        if (n.endsWith(".qed")) return "qed";
        if (n.endsWith(".hdd") || n.endsWith(".hds")) return "parallels";
        if (n.endsWith(".dmg")) return "dmg";
        if (n.endsWith(".bochs")) return "bochs";
        return null;
    }

    public static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
            // fall through
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "image";
    }

    private static boolean portFree(String proto, int port) {
        try {
            if ("udp".equals(proto)) {
                new DatagramSocket(port).close();
            } else {
                new ServerSocket(port).close();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int firstFreeTcp(int from, int to) {
        for (int p = from; p <= to; p++) {
            if (portFree("tcp", p)) return p;
        }
        return -1;
    }
}
