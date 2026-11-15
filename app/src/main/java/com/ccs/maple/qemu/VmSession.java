package com.ccs.maple.qemu;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.ccs.maple.R;
import com.ccs.maple.model.VmConfig;
import com.ccs.maple.model.VmStore;
import com.ccs.maple.util.Io;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** One running QEMU process and its control channel. */
public final class VmSession implements QmpClient.EventListener {
    private static final String TAG = "MapleVm";

    public enum State {STARTING, RUNNING, PAUSED, STOPPING, STOPPED}

    public static final int PAUSE_USER = 1;
    public static final int PAUSE_THERMAL = 2;

    public interface Listener {
        void onVmStateChanged(VmSession session);

        void onVmMessage(VmSession session, String message);
    }

    public final VmConfig vm;
    private final Context app;
    private final VmStore store;
    private final File vmDir;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile State state = State.STOPPED;
    private volatile int pid = -1;
    private volatile QmpClient qmp;
    private volatile String vncSocket;
    private volatile String qmpSocket;
    private volatile int cpus = 1;
    private volatile boolean relativePointer;
    private volatile boolean linkUp = true;
    private volatile int pauseMask;
    private volatile float[] vcpuLoad = new float[0];
    private volatile String lastError;
    private volatile boolean resumedState;
    private volatile boolean stopRequested;
    private long startedAt;

    public VmSession(Context context, VmConfig vm) {
        this.app = context.getApplicationContext();
        this.vm = vm;
        this.store = new VmStore(app);
        this.vmDir = store.dir(vm);
    }

    // ------------------------------------------------------------------ accessors

    public State state() {
        return state;
    }

    public boolean isAlive() {
        return state != State.STOPPED;
    }

    public String vncSocket() {
        return vncSocket;
    }

    public int cpus() {
        return cpus;
    }

    public boolean relativePointer() {
        return relativePointer;
    }

    public boolean linkUp() {
        return linkUp;
    }

    public int pauseMask() {
        return pauseMask;
    }

    public float[] vcpuLoad() {
        return vcpuLoad;
    }

    public String lastError() {
        return lastError;
    }

    public File logFile() {
        return new File(vmDir, "qemu.log");
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void setState(State s) {
        state = s;
        for (Listener l : listeners) l.onVmStateChanged(this);
        VmService.notifyChanged();
    }

    private void message(String text) {
        for (Listener l : listeners) l.onVmMessage(this, text);
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() {
        if (state != State.STOPPED) return;
        stopRequested = false;
        lastError = null;
        pauseMask = 0;
        linkUp = true;
        setState(State.STARTING);
        Thread t = new Thread(this::startBlocking, "vm-start");
        t.start();
    }

    private void startBlocking() {
        QemuCommandBuilder.Result cmd;
        try {
            cmd = new QemuCommandBuilder(app, vm, vmDir).build();
        } catch (Exception e) {
            Log.e(TAG, "build failed", e);
            lastError = e.getMessage();
            setState(State.STOPPED);
            VmService.onSessionStopped(this);
            return;
        }
        vncSocket = cmd.vncSocket;
        qmpSocket = cmd.qmpSocket;
        cpus = cmd.cpus;
        relativePointer = cmd.relativePointer;
        resumedState = cmd.resumesSavedState;

        int[] keep = new int[cmd.fds.size()];
        for (int i = 0; i < keep.length; i++) keep[i] = cmd.fds.get(i).getFd();
        File log = logFile();
        try {
            Io.writeText(new File(vmDir, "cmdline.txt"), String.join(" ", cmd.argv));
        } catch (IOException ignored) {
            // diagnostics only
        }
        int child = NativeBridge.spawn(cmd.exe, cmd.argv.toArray(new String[0]),
                cmd.env.toArray(new String[0]), keep, log.getAbsolutePath(),
                vmDir.getAbsolutePath());
        for (ParcelFileDescriptor fd : cmd.fds) {
            try {
                fd.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
        if (child <= 0) {
            lastError = app.getString(R.string.err_spawn);
            setState(State.STOPPED);
            VmService.onSessionStopped(this);
            return;
        }
        pid = child;
        startedAt = SystemClock.elapsedRealtime();
        for (String w : cmd.warnings) message(w);

        Thread waiter = new Thread(() -> onExit(NativeBridge.waitFor(child)), "vm-wait");
        waiter.start();

        // The QMP socket appears once QEMU has parsed its command line.
        QmpClient client = null;
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (state == State.STARTING && SystemClock.elapsedRealtime() < deadline) {
            if (new File(cmd.qmpSocket).exists()) {
                QmpClient c = new QmpClient();
                try {
                    c.connect(cmd.qmpSocket);
                    client = c;
                    break;
                } catch (IOException e) {
                    c.close();
                }
            }
            SystemClock.sleep(120);
        }
        if (state != State.STARTING) return; // process already died; onExit reported it
        if (client == null) {
            lastError = app.getString(R.string.err_qmp);
            NativeBridge.kill(child, 9);
            return;
        }
        client.setListener(this);
        qmp = client;

        if (resumedState) {
            // The snapshot has been consumed; a later crash must cold boot.
            vm.hasSuspendState = false;
            store.save(vm);
        }
        vm.lastUsed = System.currentTimeMillis();
        store.save(vm);

        setState(pauseMask != 0 ? State.PAUSED : State.RUNNING);
        if (pauseMask != 0) {
            try {
                client.execute("stop");
            } catch (IOException ignored) {
                // will be retried by the next pause request
            }
        }
        Thread mon = new Thread(this::monitorLoop, "vm-monitor");
        mon.setDaemon(true);
        mon.start();
    }

    private void onExit(int code) {
        Log.i(TAG, vm.name + " exited with " + code);
        QmpClient c = qmp;
        qmp = null;
        if (c != null) c.close();
        pid = -1;
        if (vncSocket != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(vncSocket).delete();
        }
        if (qmpSocket != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(qmpSocket).delete();
        }
        if (!stopRequested && code != 0) {
            String tail = Io.tail(logFile(), 600).trim();
            if (lastError == null) {
                lastError = tail.isEmpty() ? app.getString(R.string.err_exit, code) : tail;
            }
            if (resumedState && SystemClock.elapsedRealtime() - startedAt < 20000) {
                // The saved state could not be restored (hardware changed?). Drop it so the
                // next start is a normal boot.
                vm.hasSuspendState = false;
                store.save(vm);
                lastError = app.getString(R.string.err_resume_failed) + "\n" + lastError;
            }
        }
        vcpuLoad = new float[0];
        setState(State.STOPPED);
        VmService.onSessionStopped(this);
    }

    /** Stops the VM. With {@code allowSuspend} the VM state is saved first if configured. */
    public void stop(boolean allowSuspend) {
        if (state == State.STOPPED || state == State.STOPPING) return;
        stopRequested = true;
        final QmpClient c = qmp;
        final int child = pid;
        setState(State.STOPPING);
        new Thread(() -> {
            if (c != null) {
                if (allowSuspend && vm.suspendOnStop) {
                    try {
                        c.execute("stop");
                        String out = c.hmp("savevm " + VmConfig.SUSPEND_TAG, 15 * 60 * 1000L);
                        if (out.trim().isEmpty()) {
                            vm.hasSuspendState = true;
                            store.save(vm);
                        } else {
                            message(app.getString(R.string.err_save_state, out.trim()));
                        }
                    } catch (IOException e) {
                        message(app.getString(R.string.err_save_state, e.getMessage()));
                    }
                }
                try {
                    c.execute("quit", null, 3000);
                } catch (IOException ignored) {
                    // the socket closes as QEMU exits
                }
            }
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (state != State.STOPPED && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100);
            }
            if (state != State.STOPPED && child > 0) NativeBridge.kill(child, 9);
        }, "vm-stop").start();
    }

    // ------------------------------------------------------------------ control

    public void pause(int reason) {
        boolean was = pauseMask != 0;
        pauseMask |= reason;
        if (!was && state == State.RUNNING) {
            async(() -> {
                qmpOrThrow().execute("stop");
                setState(State.PAUSED);
            });
        }
    }

    public void resume(int reason) {
        if ((pauseMask & reason) == 0) return;
        pauseMask &= ~reason;
        if (pauseMask == 0 && state == State.PAUSED) {
            async(() -> {
                qmpOrThrow().execute("cont");
                setState(State.RUNNING);
            });
        }
    }

    public void setLink(boolean up) {
        async(() -> {
            JSONObject args = new JSONObject();
            try {
                args.put("name", "nic0");
                args.put("up", up);
            } catch (JSONException e) {
                throw new IOException(e);
            }
            qmpOrThrow().execute("set_link", args, 5000);
            linkUp = up;
            setState(state);
        });
    }

    public void powerButton() {
        async(() -> qmpOrThrow().execute("system_powerdown"));
    }

    public void reset() {
        async(() -> qmpOrThrow().execute("system_reset"));
    }

    /** Saves a named state; reports the outcome through {@link Listener#onVmMessage}. */
    public void saveState(String tag) {
        async(() -> {
            String out = qmpOrThrow().hmp("savevm " + tag, 15 * 60 * 1000L).trim();
            message(out.isEmpty() ? app.getString(R.string.msg_state_saved)
                    : app.getString(R.string.err_save_state, out));
        });
    }

    public void loadState(String tag) {
        async(() -> {
            String out = qmpOrThrow().hmp("loadvm " + tag, 15 * 60 * 1000L).trim();
            message(out.isEmpty() ? app.getString(R.string.msg_state_loaded)
                    : app.getString(R.string.err_load_state, out));
            // loadvm leaves the VM in the run state it had before the command.
        });
    }

    private interface QmpTask {
        void run() throws IOException;
    }

    private void async(QmpTask task) {
        new Thread(() -> {
            try {
                task.run();
            } catch (IOException e) {
                message(e.getMessage());
            }
        }, "vm-qmp").start();
    }

    private QmpClient qmpOrThrow() throws IOException {
        QmpClient c = qmp;
        if (c == null) throw new IOException(app.getString(R.string.err_not_running));
        return c;
    }

    @Override
    public void onQmpEvent(String name, JSONObject data) {
        Log.d(TAG, vm.name + " event " + name);
    }

    // ------------------------------------------------------------------ vCPU load and pinning

    private void monitorLoop() {
        int[] tids = null;
        long[] lastTicks = null;
        long lastTime = 0;
        int[] assigned = null;
        long tck = 100;
        try {
            tck = Os.sysconf(OsConstants._SC_CLK_TCK);
        } catch (Exception ignored) {
            // keep the Linux default
        }
        int tick = 0;
        while (state == State.RUNNING || state == State.PAUSED) {
            SystemClock.sleep(1000);
            int child = pid;
            QmpClient c = qmp;
            if (child <= 0 || c == null) break;
            try {
                if (tids == null) {
                    tids = queryVcpuThreads(c);
                    if (tids.length == 0) {
                        tids = null;
                        continue;
                    }
                    lastTicks = new long[tids.length];
                    for (int i = 0; i < tids.length; i++) lastTicks[i] = cpuTicks(child, tids[i]);
                    lastTime = SystemClock.elapsedRealtime();
                    assigned = new int[tids.length];
                    java.util.Arrays.fill(assigned, -1);
                    continue;
                }
                long now = SystemClock.elapsedRealtime();
                float dt = (now - lastTime) / 1000f;
                float[] load = new float[tids.length];
                for (int i = 0; i < tids.length; i++) {
                    long t = cpuTicks(child, tids[i]);
                    load[i] = dt > 0 ? Math.min(1f, (t - lastTicks[i]) / (float) tck / dt) : 0f;
                    lastTicks[i] = t;
                }
                lastTime = now;
                vcpuLoad = load;
                if ((tick++ % 2) == 0) rebalance(tids, load, assigned);
            } catch (Exception e) {
                Log.w(TAG, "monitor: " + e);
            }
        }
    }

    private static int[] queryVcpuThreads(QmpClient c) throws IOException {
        JSONArray arr = c.execute("query-cpus-fast").optJSONArray("return");
        if (arr == null) return new int[0];
        int[] tids = new int[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            int idx = o.optInt("cpu-index", i);
            if (idx >= 0 && idx < tids.length) tids[idx] = o.optInt("thread-id", 0);
        }
        return tids;
    }

    private static long cpuTicks(int pid, int tid) {
        try {
            String s = Io.readText(new File("/proc/" + pid + "/task/" + tid + "/stat"));
            String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(f[11]) + Long.parseLong(f[12]); // utime + stime
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Gives every vCPU thread its own host core. The busiest vCPUs get the fastest cores; the
     * Sustained profile leaves the hottest cores unused.
     */
    private void rebalance(int[] tids, float[] load, int[] assigned) {
        CpuTopology topo = CpuTopology.get();
        List<Integer> cores = new ArrayList<>();
        int skip = 0;
        if (VmConfig.PROFILE_SUSTAINED.equals(vm.profile)) {
            skip = (topo.performanceCores() + 1) / 2;
            if (topo.count - skip < tids.length) skip = Math.max(0, topo.count - tids.length);
        }
        for (int i = skip; i < topo.count; i++) cores.add(topo.byPerformance[i]);
        if (cores.size() < tids.length) {
            // More vCPUs than cores: let the scheduler float them.
            for (int i = 0; i < tids.length; i++) {
                if (assigned[i] != -2) {
                    NativeBridge.setAffinity(tids[i], topo.allMask());
                    assigned[i] = -2;
                }
            }
            return;
        }
        Integer[] order = new Integer[tids.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        // Hysteresis: a vCPU keeps its rank unless another one is clearly busier.
        java.util.Arrays.sort(order, (a, b) -> {
            float d = load[b] - load[a];
            if (Math.abs(d) < 0.15f) return Integer.compare(a, b);
            return d > 0 ? 1 : -1;
        });
        for (int rank = 0; rank < order.length; rank++) {
            int v = order[rank];
            int core = cores.get(rank);
            if (assigned[v] != core && tids[v] > 0) {
                if (NativeBridge.setAffinity(tids[v], 1L << core) == 0) assigned[v] = core;
            }
        }
    }
}
