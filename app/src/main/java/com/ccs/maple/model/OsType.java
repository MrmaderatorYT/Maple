package com.ccs.maple.model;

import com.ccs.maple.R;

/** Guest operating system family; drives the virtual hardware chosen for a VM. */
public enum OsType {
    LINUX(R.string.os_linux),
    ANDROID(R.string.os_android),
    WIN_XP(R.string.os_win_xp),
    WIN_7(R.string.os_win_7),
    WIN_10(R.string.os_win_10),
    WIN_11(R.string.os_win_11),
    FREEBSD(R.string.os_freebsd),
    OPENBSD(R.string.os_openbsd),
    NETBSD(R.string.os_netbsd),
    DOS(R.string.os_dos),
    REACTOS(R.string.os_reactos),
    HAIKU(R.string.os_haiku),
    OTHER(R.string.os_other);

    public final int labelRes;

    OsType(int labelRes) {
        this.labelRes = labelRes;
    }

    public boolean isWindows() {
        return this == WIN_XP || this == WIN_7 || this == WIN_10 || this == WIN_11;
    }

    /** Guests that keep the hardware clock in local time. */
    public boolean usesLocalTime() {
        return isWindows() || this == DOS || this == REACTOS;
    }

    public static OsType fromName(String name) {
        if (name != null) {
            for (OsType t : values()) {
                if (t.name().equalsIgnoreCase(name)) return t;
            }
        }
        return OTHER;
    }

    /** Maps the "os" field of the OS library catalog. */
    public static OsType fromCatalog(String os) {
        if (os == null) return OTHER;
        switch (os) {
            case "linux":
                return LINUX;
            case "freebsd":
                return FREEBSD;
            case "openbsd":
                return OPENBSD;
            case "netbsd":
                return NETBSD;
            case "dos":
                return DOS;
            case "reactos":
                return REACTOS;
            case "haiku":
                return HAIKU;
            case "android":
                return ANDROID;
            default:
                return OTHER;
        }
    }
}
