package com.ccs.maple.qemu;

import android.graphics.Bitmap;

/** JNI entry points implemented in src/main/cpp/maple_jni.c. */
public final class NativeBridge {
    static {
        System.loadLibrary("maple");
    }

    private NativeBridge() {
    }

    /**
     * Forks and execs {@code exe}. Descriptors listed in {@code keepFds} stay open in the child
     * under the same numbers; everything else above stderr is closed.
     *
     * @return child pid, or -1
     */
    public static native int spawn(String exe, String[] argv, String[] envp, int[] keepFds,
                                   String logPath, String cwd);

    /** Blocks until the child exits; returns exit code, 128+signal, or -1. */
    public static native int waitFor(int pid);

    public static native int kill(int pid, int signal);

    /** Pins thread {@code tid} to the host cores set in {@code mask} (bit n = cpu n). */
    public static native int setAffinity(int tid, long mask);

    public static native int setNice(int tid, int nice);

    /** Copies a raw RFB rectangle (R,G,B,X bytes) into an ARGB_8888 bitmap. */
    public static native boolean blit(Bitmap bitmap, byte[] src, int x, int y, int w, int h);
}
