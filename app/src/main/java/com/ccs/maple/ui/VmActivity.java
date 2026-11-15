package com.ccs.maple.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Rational;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.ccs.maple.R;
import com.ccs.maple.display.Keymap;
import com.ccs.maple.display.VmDisplayView;
import com.ccs.maple.model.VmConfig;
import com.ccs.maple.model.VmStore;
import com.ccs.maple.qemu.VmService;
import com.ccs.maple.qemu.VmSession;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Full-screen guest display with the extra-keys bar, HUD and VM controls. */
public final class VmActivity extends Activity implements VmSession.Listener,
        VmDisplayView.Callback {
    private static final String EXTRA_ID = "vm";
    private static final String STATE_TAG = "maple-manual";

    public static Intent intent(Context c, String vmId) {
        // A distinct data URI per VM gives every running VM its own entry in Recents.
        return new Intent(c, VmActivity.class)
                .setData(Uri.parse("maple://vm/" + vmId))
                .putExtra(EXTRA_ID, vmId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private VmStore store;
    private VmConfig vm;
    private VmSession session;
    private VmDisplayView display;
    private TextView status;
    private View menuButton;
    private HorizontalScrollView keysScroll;
    private final Map<Integer, Button> stickyButtons = new HashMap<>();
    private boolean errorShown;
    private boolean inPip;

    private final Runnable hudTick = new Runnable() {
        @Override
        public void run() {
            updateHud();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new VmStore(this);
        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id == null && getIntent().getData() != null) {
            id = getIntent().getData().getLastPathSegment();
        }
        vm = store.get(id);
        if (vm == null) {
            Toast.makeText(this, R.string.err_vm_missing, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        VmSession live = VmService.get(vm.id);
        if (live != null && live.isAlive()) vm = live.vm; // share the running instance's config

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        applyOrientation();
        buildUi();
        setTaskDescription(new android.app.ActivityManager.TaskDescription(vm.name));

        session = VmService.start(this, vm);
        session.addListener(this);
        onVmStateChanged(session);
    }

    private void applyOrientation() {
        switch (vm.orientation) {
            case VmConfig.ORIENT_LANDSCAPE:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                break;
            case VmConfig.ORIENT_PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                break;
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        FrameLayout stage = new FrameLayout(this);
        display = new VmDisplayView(this);
        display.setCallback(this);
        display.setScaling(vm.scaling);
        display.setInputMode(vm.inputMode);
        display.setPointerSpeed(vm.pointerSpeed);
        display.setHighRefresh(vm.highRefresh);
        stage.addView(display, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0x99000000);
        status.setPadding(dp(18), dp(10), dp(18), dp(10));
        stage.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        Button menu = new Button(this, null, android.R.attr.borderlessButtonStyle);
        menu.setText("⋮");
        menu.setTextSize(22);
        menu.setTextColor(Color.WHITE);
        menu.setAlpha(0.55f);
        menu.setBackgroundColor(0x55000000);
        menu.setOnClickListener(this::showMenu);
        menuButton = menu;
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.TOP | Gravity.END);
        mlp.setMargins(0, dp(6), dp(6), 0);
        stage.addView(menu, mlp);

        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0,
                1f));

        keysScroll = new HorizontalScrollView(this);
        keysScroll.setBackgroundColor(0xFF1B1B1F);
        keysScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout keys = new LinearLayout(this);
        keysScroll.addView(keys);
        addKey(keys, "⌨", v -> toggleKeyboard());
        addKey(keys, "Esc", v -> display.tapKey(Keymap.ESC));
        addKey(keys, "Tab", v -> display.tapKey(Keymap.TAB));
        addSticky(keys, "Ctrl", Keymap.CTRL);
        addSticky(keys, "Alt", Keymap.ALT);
        addSticky(keys, "Shift", Keymap.SHIFT);
        addSticky(keys, "Win", Keymap.WIN);
        addKey(keys, "←", v -> display.tapKey(Keymap.LEFT));
        addKey(keys, "↑", v -> display.tapKey(Keymap.UP));
        addKey(keys, "↓", v -> display.tapKey(Keymap.DOWN));
        addKey(keys, "→", v -> display.tapKey(Keymap.RIGHT));
        addKey(keys, "Del", v -> display.tapKey(Keymap.DELETE));
        addKey(keys, "Ins", v -> display.tapKey(Keymap.INSERT));
        addKey(keys, "Home", v -> display.tapKey(Keymap.HOME));
        addKey(keys, "End", v -> display.tapKey(Keymap.END));
        addKey(keys, "PgUp", v -> display.tapKey(Keymap.PGUP));
        addKey(keys, "PgDn", v -> display.tapKey(Keymap.PGDN));
        for (int i = 1; i <= 12; i++) {
            final int q = Keymap.fkey(i);
            addKey(keys, "F" + i, v -> display.tapKey(q));
        }
        addKey(keys, "PrtSc", v -> display.tapKey(Keymap.PRINT));
        addKey(keys, "Menu", v -> display.tapKey(Keymap.MENU));
        root.addView(keysScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        keysScroll.setVisibility(vm.showKeys ? View.VISIBLE : View.GONE);

        setContentView(root);
        display.requestFocus();
    }

    private Button addKey(LinearLayout bar, String label, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setMinWidth(dp(46));
        b.setMinimumWidth(dp(46));
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setFocusable(false);
        b.setOnClickListener(l);
        bar.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40)));
        return b;
    }

    private void addSticky(LinearLayout bar, String label, int qnum) {
        stickyButtons.put(qnum, addKey(bar, label, v -> display.toggleSticky(qnum)));
    }

    @Override
    public void onStickyKeysChanged(Set<Integer> active) {
        for (Map.Entry<Integer, Button> e : stickyButtons.entrySet()) {
            boolean on = active.contains(e.getKey());
            e.getValue().setTextColor(on ? getColor(R.color.accent) : Color.WHITE);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        ui.post(hudTick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(hudTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (session != null) session.removeListener(this);
        if (display != null) display.disconnect();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    // ------------------------------------------------------------------ session events

    @Override
    public void onVmStateChanged(VmSession s) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            switch (s.state()) {
                case STARTING:
                    showStatus(getString(vm.hasSuspendState ? R.string.status_resuming
                            : R.string.status_starting));
                    break;
                case RUNNING:
                    showStatus(null);
                    connectDisplay();
                    break;
                case PAUSED:
                    showStatus(getString((s.pauseMask() & VmSession.PAUSE_THERMAL) != 0
                            ? R.string.status_paused_heat : R.string.status_paused));
                    connectDisplay();
                    break;
                case STOPPING:
                    showStatus(getString(vm.suspendOnStop ? R.string.status_saving
                            : R.string.status_stopping));
                    break;
                case STOPPED:
                    display.disconnect();
                    String err = s.lastError();
                    if (err != null && !errorShown) {
                        errorShown = true;
                        showStatus(null);
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.err_vm_stopped)
                                .setMessage(err)
                                .setCancelable(false)
                                .setPositiveButton(android.R.string.ok, (d, w) -> finishAndRemoveTask())
                                .show();
                    } else if (err == null) {
                        finishAndRemoveTask();
                    }
                    break;
                default:
                    break;
            }
        });
    }

    @Override
    public void onVmMessage(VmSession s, String message) {
        if (message == null) return;
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private void connectDisplay() {
        if (!display.isConnected() && session.vncSocket() != null) {
            display.connect(session.vncSocket(), vm.sound, session.relativePointer());
        }
    }

    private void showStatus(String text) {
        if (text == null) {
            status.setVisibility(View.GONE);
        } else {
            status.setText(text);
            status.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDisplayConnected() {
        // nothing: frames start arriving
    }

    @Override
    public void onDisplayDisconnected(String reason) {
        // The VM may still be alive (e.g. the display device was reset); try again shortly.
        ui.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || session == null) return;
            VmSession.State st = session.state();
            if (st == VmSession.State.RUNNING || st == VmSession.State.PAUSED) {
                display.disconnect();
                connectDisplay();
            }
        }, 500);
    }

    // ------------------------------------------------------------------ HUD

    private void updateHud() {
        if (display == null || session == null) return;
        int fps = display.takeFrameCount();
        if (!vm.showHud) {
            display.setHud(false, null, null);
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("fps  " + fps);
        if (Build.VERSION.SDK_INT >= 30) {
            float headroom = getSystemService(PowerManager.class).getThermalHeadroom(0);
            if (!Float.isNaN(headroom)) {
                // 1.0 is the point at which the system starts throttling hard.
                lines.add("heat " + Math.round(headroom * 100) + "%");
            }
        }
        lines.add("net  " + (!vm.network ? "off" : session.linkUp() ? "up" : "unplugged"));
        display.setHud(true, lines.toArray(new String[0]), session.vcpuLoad());
    }

    // ------------------------------------------------------------------ menu

    private static final int M_KEYBOARD = 1, M_KEYS = 2, M_HUD = 3, M_PAUSE = 4, M_CABLE = 5,
            M_SHOT = 6, M_SAVE = 7, M_LOAD = 8, M_CAD = 9, M_PIP = 10, M_POWER = 11,
            M_RESET = 12, M_STOP = 13, M_IN_TRACK = 20, M_IN_TOUCH = 21, M_IN_MOUSE = 22,
            M_SC_FIT = 30, M_SC_STRETCH = 31, M_SC_INT = 32, M_KILL = 14;

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        boolean paused = (session.pauseMask() & VmSession.PAUSE_USER) != 0;
        m.add(0, M_KEYBOARD, 0, R.string.m_keyboard);
        m.add(0, M_KEYS, 0, vm.showKeys ? R.string.m_hide_keys : R.string.m_show_keys);
        SubMenu in = m.addSubMenu(R.string.f_input_mode);
        in.add(1, M_IN_TRACK, 0, R.string.input_trackpad)
                .setChecked(VmConfig.INPUT_TRACKPAD.equals(vm.inputMode));
        in.add(1, M_IN_TOUCH, 0, R.string.input_touch)
                .setChecked(VmConfig.INPUT_TOUCH.equals(vm.inputMode));
        in.add(1, M_IN_MOUSE, 0, R.string.input_mouse)
                .setChecked(VmConfig.INPUT_MOUSE.equals(vm.inputMode));
        in.setGroupCheckable(1, true, true);
        SubMenu sc = m.addSubMenu(R.string.f_scaling);
        sc.add(2, M_SC_FIT, 0, R.string.scale_fit)
                .setChecked(VmConfig.SCALE_FIT.equals(vm.scaling));
        sc.add(2, M_SC_STRETCH, 0, R.string.scale_stretch)
                .setChecked(VmConfig.SCALE_STRETCH.equals(vm.scaling));
        sc.add(2, M_SC_INT, 0, R.string.scale_integer)
                .setChecked(VmConfig.SCALE_INTEGER.equals(vm.scaling));
        sc.setGroupCheckable(2, true, true);
        m.add(0, M_HUD, 0, vm.showHud ? R.string.m_hide_hud : R.string.m_show_hud);
        m.add(0, M_PAUSE, 0, paused ? R.string.m_resume : R.string.m_pause);
        if (vm.network) {
            m.add(0, M_CABLE, 0, session.linkUp() ? R.string.m_unplug : R.string.m_plug);
        }
        m.add(0, M_SHOT, 0, R.string.m_screenshot);
        m.add(0, M_SAVE, 0, R.string.m_save_state);
        m.add(0, M_LOAD, 0, R.string.m_load_state);
        m.add(0, M_CAD, 0, "Ctrl+Alt+Del");
        m.add(0, M_PIP, 0, R.string.m_pip);
        m.add(0, M_POWER, 0, R.string.m_power_button);
        m.add(0, M_RESET, 0, R.string.m_reset);
        m.add(0, M_STOP, 0, vm.suspendOnStop ? R.string.m_suspend : R.string.action_stop);
        if (vm.suspendOnStop) m.add(0, M_KILL, 0, R.string.m_stop_no_save);
        pm.setOnMenuItemClickListener(item -> {
            onMenu(item.getItemId());
            return true;
        });
        pm.show();
    }

    private void onMenu(int id) {
        switch (id) {
            case M_KEYBOARD:
                toggleKeyboard();
                break;
            case M_KEYS:
                vm.showKeys = !vm.showKeys;
                keysScroll.setVisibility(vm.showKeys ? View.VISIBLE : View.GONE);
                store.save(vm);
                break;
            case M_HUD:
                vm.showHud = !vm.showHud;
                store.save(vm);
                updateHud();
                break;
            case M_PAUSE:
                if ((session.pauseMask() & VmSession.PAUSE_USER) != 0) {
                    session.resume(VmSession.PAUSE_USER);
                } else {
                    session.pause(VmSession.PAUSE_USER);
                }
                break;
            case M_CABLE:
                session.setLink(!session.linkUp());
                break;
            case M_SHOT:
                screenshot();
                break;
            case M_SAVE:
                Toast.makeText(this, R.string.msg_saving_state, Toast.LENGTH_SHORT).show();
                session.saveState(STATE_TAG);
                break;
            case M_LOAD:
                session.loadState(STATE_TAG);
                break;
            case M_CAD:
                display.chord(Keymap.CTRL, Keymap.ALT, Keymap.DELETE);
                break;
            case M_PIP:
                enterPip();
                break;
            case M_POWER:
                session.powerButton();
                break;
            case M_RESET:
                session.reset();
                break;
            case M_STOP:
                session.stop(true);
                break;
            case M_KILL:
                session.stop(false);
                break;
            case M_IN_TRACK:
                setInput(VmConfig.INPUT_TRACKPAD);
                break;
            case M_IN_TOUCH:
                setInput(VmConfig.INPUT_TOUCH);
                break;
            case M_IN_MOUSE:
                setInput(VmConfig.INPUT_MOUSE);
                break;
            case M_SC_FIT:
                setScaling(VmConfig.SCALE_FIT);
                break;
            case M_SC_STRETCH:
                setScaling(VmConfig.SCALE_STRETCH);
                break;
            case M_SC_INT:
                setScaling(VmConfig.SCALE_INTEGER);
                break;
            default:
                break;
        }
    }

    private void setInput(String mode) {
        vm.inputMode = mode;
        display.setInputMode(mode);
        store.save(vm);
    }

    private void setScaling(String mode) {
        vm.scaling = mode;
        display.setScaling(mode);
        store.save(vm);
    }

    private void toggleKeyboard() {
        display.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            boolean shown = insets != null && insets.isVisible(WindowInsets.Type.ime());
            if (shown) imm.hideSoftInputFromWindow(display.getWindowToken(), 0);
            else imm.showSoftInput(display, 0);
        } else {
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    private void screenshot() {
        Bitmap shot = display.snapshot();
        if (shot == null) {
            Toast.makeText(this, R.string.err_screenshot, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            String error = null;
            try {
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                        .format(new Date());
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "Maple-" + stamp + ".png");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Maple");
                Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new java.io.IOException("MediaStore refused the image");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null || !shot.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw new java.io.IOException("Cannot write image");
                    }
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String err = error;
            ui.post(() -> Toast.makeText(this, err == null
                    ? getString(R.string.msg_screenshot_saved)
                    : getString(R.string.err_screenshot) + ": " + err, Toast.LENGTH_LONG).show());
        }, "screenshot").start();
    }

    // ------------------------------------------------------------------ picture in picture

    private void enterPip() {
        Bitmap fb = display.snapshot();
        Rational ratio = new Rational(4, 3);
        if (fb != null) {
            float r = fb.getWidth() / (float) fb.getHeight();
            // The system only accepts aspect ratios between 1:2.39 and 2.39:1.
            r = Math.max(0.42f, Math.min(2.39f, r));
            ratio = new Rational(Math.round(r * 1000), 1000);
            fb.recycle();
        }
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(ratio).build());
        } catch (Exception e) {
            Toast.makeText(this, R.string.err_pip, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean pip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(pip, newConfig);
        inPip = pip;
        menuButton.setVisibility(pip ? View.GONE : View.VISIBLE);
        keysScroll.setVisibility(!pip && vm.showKeys ? View.VISIBLE : View.GONE);
        if (pip) display.setHud(false, null, null);
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (display != null && display.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!inPip && menuButton != null) showMenu(menuButton);
    }
}
