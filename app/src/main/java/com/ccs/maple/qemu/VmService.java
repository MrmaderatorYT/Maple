package com.ccs.maple.qemu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.ccs.maple.R;
import com.ccs.maple.model.VmConfig;
import com.ccs.maple.ui.MainActivity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service that keeps running VMs alive while the app is in the background and
 * guards them: VMs pause when the phone overheats and resume once it has cooled down, and a
 * warning is posted when the battery runs low.
 */
public final class VmService extends Service {
    private static final String CHANNEL_RUN = "running";
    private static final String CHANNEL_ALERT = "alerts";
    private static final int NOTE_RUN = 1;
    private static final int NOTE_BATTERY = 2;
    private static final int NOTE_HEAT = 3;

    private static final Map<String, VmSession> SESSIONS = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Runnable> CHANGE_LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile VmService instance;

    // ------------------------------------------------------------------ static registry

    public static VmSession get(String id) {
        return id == null ? null : SESSIONS.get(id);
    }

    public static Collection<VmSession> all() {
        return new ArrayList<>(SESSIONS.values());
    }

    public static boolean isRunning(String id) {
        VmSession s = get(id);
        return s != null && s.isAlive();
    }

    /** Returns the live session for this VM, starting it if needed. */
    public static VmSession start(Context context, VmConfig vm) {
        VmSession existing = SESSIONS.get(vm.id);
        if (existing != null && existing.isAlive()) return existing;
        VmSession s = new VmSession(context, vm);
        SESSIONS.put(vm.id, s);
        Context app = context.getApplicationContext();
        app.startForegroundService(new Intent(app, VmService.class));
        s.start();
        return s;
    }

    static void onSessionStopped(VmSession s) {
        // The stopped session stays readable (lastError) until the same VM is started again.
        notifyChanged();
    }

    public static void addChangeListener(Runnable r) {
        CHANGE_LISTENERS.addIfAbsent(r);
    }

    public static void removeChangeListener(Runnable r) {
        CHANGE_LISTENERS.remove(r);
    }

    static void notifyChanged() {
        MAIN.post(() -> {
            for (Runnable r : CHANGE_LISTENERS) r.run();
            VmService svc = instance;
            if (svc != null) svc.refresh();
        });
    }

    private static List<VmSession> alive() {
        List<VmSession> out = new ArrayList<>();
        for (VmSession s : SESSIONS.values()) {
            if (s.isAlive()) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ service

    private PowerManager power;
    private final PowerManager.OnThermalStatusChangedListener thermalListener = this::onThermal;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_LOW.equals(intent.getAction()) && !alive().isEmpty()) {
                alert(NOTE_BATTERY, getString(R.string.note_battery_title),
                        getString(R.string.note_battery_text));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_RUN,
                getString(R.string.channel_running), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT,
                getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH));
        power = getSystemService(PowerManager.class);
        power.addThermalStatusListener(getMainExecutor(), thermalListener);
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTE_RUN, n);
        }
        // A VM that failed before the service came up must not leave the notification behind.
        if (alive().isEmpty()) refresh();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        power.removeThermalStatusListener(thermalListener);
        unregisterReceiver(batteryReceiver);
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // VMs keep running when the task is swiped away; the notification brings the UI back.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void refresh() {
        if (alive().isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else {
            getSystemService(NotificationManager.class).notify(NOTE_RUN, buildNotification());
        }
    }

    private Notification buildNotification() {
        List<VmSession> live = alive();
        StringBuilder names = new StringBuilder();
        for (VmSession s : live) {
            if (names.length() > 0) names.append(", ");
            names.append(s.vm.name);
            if (s.state() == VmSession.State.PAUSED) {
                names.append(" (").append(getString(R.string.state_paused)).append(')');
            }
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_RUN)
                .setSmallIcon(R.drawable.ic_stat_vm)
                .setContentTitle(getResources().getQuantityString(R.plurals.note_running,
                        Math.max(1, live.size()), Math.max(1, live.size())))
                .setContentText(names.toString())
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void alert(int id, String title, String text) {
        Notification n = new Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_vm)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(id, n);
    }

    private void onThermal(int status) {
        boolean pausedAny = false;
        for (VmSession s : alive()) {
            boolean sustained = VmConfig.PROFILE_SUSTAINED.equals(s.vm.profile);
            int limit = sustained ? PowerManager.THERMAL_STATUS_MODERATE
                    : PowerManager.THERMAL_STATUS_SEVERE;
            if (status >= limit) {
                if ((s.pauseMask() & VmSession.PAUSE_THERMAL) == 0) {
                    s.pause(VmSession.PAUSE_THERMAL);
                    pausedAny = true;
                }
            } else if (status <= limit - 2) {
                s.resume(VmSession.PAUSE_THERMAL);
            }
        }
        if (pausedAny) {
            alert(NOTE_HEAT, getString(R.string.note_heat_title),
                    getString(R.string.note_heat_text));
        } else if (status <= PowerManager.THERMAL_STATUS_LIGHT) {
            getSystemService(NotificationManager.class).cancel(NOTE_HEAT);
        }
    }
}
