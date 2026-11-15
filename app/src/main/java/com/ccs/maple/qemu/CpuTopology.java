package com.ccs.maple.qemu;

import com.ccs.maple.util.Io;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Host core layout: which cores are the fast ones on a big.LITTLE phone. */
public final class CpuTopology {
    /** Core indices sorted from fastest to slowest. */
    public final int[] byPerformance;
    public final long[] maxFreqKhz;
    public final int count;

    private static CpuTopology cached;

    private CpuTopology(int[] order, long[] freq) {
        this.byPerformance = order;
        this.maxFreqKhz = freq;
        this.count = order.length;
    }

    public static synchronized CpuTopology get() {
        if (cached != null) return cached;
        int n = Runtime.getRuntime().availableProcessors();
        long[] freq = new long[n];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            freq[i] = readFreq(i);
            order.add(i);
        }
        // Stable sort: equal-frequency cores keep their index order, highest index first on ties
        // because vendors number the prime core last.
        order.sort((a, b) -> {
            int c = Long.compare(freq[b], freq[a]);
            return c != 0 ? c : Integer.compare(b, a);
        });
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = order.get(i);
        cached = new CpuTopology(arr, freq);
        return cached;
    }

    private static long readFreq(int cpu) {
        try {
            File f = new File("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/cpuinfo_max_freq");
            return Long.parseLong(Io.readText(f).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Number of cores that are not in the slowest cluster (all cores on symmetric CPUs). */
    public int performanceCores() {
        long min = Long.MAX_VALUE;
        long max = 0;
        for (long f : maxFreqKhz) {
            min = Math.min(min, f);
            max = Math.max(max, f);
        }
        if (max == min) return count;
        int n = 0;
        for (long f : maxFreqKhz) {
            if (f > min) n++;
        }
        return Math.max(1, n);
    }

    /** Affinity mask covering the {@code n} fastest cores. */
    public long fastestMask(int n) {
        long mask = 0;
        for (int i = 0; i < Math.min(n, count); i++) mask |= 1L << byPerformance[i];
        return mask;
    }

    /** Affinity mask covering the {@code n} slowest cores. */
    public long slowestMask(int n) {
        long mask = 0;
        for (int i = 0; i < Math.min(n, count); i++) mask |= 1L << byPerformance[count - 1 - i];
        return mask;
    }

    public long allMask() {
        return count >= 64 ? -1L : (1L << count) - 1;
    }
}
