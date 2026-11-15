package com.ccs.maple.display;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.ccs.maple.model.VmConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Shows the guest screen and turns touches, mice and keyboards into guest input.
 *
 * <ul>
 * <li>Trackpad mode: drag to move the pointer, tap to click, two-finger tap for right click,
 * two-finger drag to scroll, double-tap-and-drag to drag, long press for right click.</li>
 * <li>Direct touch mode: the pointer jumps to the finger; tap clicks, long press right-clicks.</li>
 * <li>Mouse mode: a physical mouse is captured and drives the guest pointer directly.</li>
 * </ul>
 */
public final class VmDisplayView extends SurfaceView implements SurfaceHolder.Callback,
        RfbClient.Listener {

    public interface Callback {
        void onDisplayConnected();

        void onDisplayDisconnected(String reason);

        void onStickyKeysChanged(Set<Integer> active);
    }

    private static final int BTN_LEFT = 1;
    private static final int BTN_MIDDLE = 2;
    private static final int BTN_RIGHT = 4;
    private static final int BTN_WHEEL_UP = 8;
    private static final int BTN_WHEEL_DOWN = 16;

    private final Object renderLock = new Object();
    private final Paint bitmapPaint = new Paint();
    private final Paint hudText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBg = new Paint();
    private final Paint hudBar = new Paint();
    private final Matrix matrix = new Matrix();
    private final Set<Integer> sticky = new HashSet<>();
    private final float density;
    private final int touchSlop;

    private RfbClient rfb;
    private Callback callback;
    private Thread renderThread;
    private volatile boolean surfaceReady;
    private volatile boolean dirty;
    private volatile boolean running;

    private String scaling = VmConfig.SCALE_FIT;
    private String inputMode = VmConfig.INPUT_TRACKPAD;
    private float pointerSpeed = 1f;
    private boolean highRefresh = true;

    // View transform: view = guest * scale + offset
    private volatile float scaleX = 1f;
    private volatile float scaleY = 1f;
    private volatile float offX;
    private volatile float offY;

    // Guest pointer state
    private float cx;
    private float cy;
    private float relAccX;
    private float relAccY;
    private int buttons;
    private volatile Bitmap cursor;
    private volatile int cursorHotX;
    private volatile int cursorHotY;

    // Gesture state
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private float lastScrollY;
    private float scrollAcc;
    private boolean moved;
    private boolean dragging;
    private boolean tapDrag;
    private boolean longPressFired;
    private int maxPointers;
    private long lastTapUp;

    // HUD
    private volatile boolean hudVisible;
    private volatile String[] hudLines = new String[0];
    private volatile float[] hudLoads = new float[0];

    public VmDisplayView(Context context) {
        this(context, null);
    }

    public VmDisplayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        hudText.setColor(Color.WHITE);
        hudText.setTextSize(11 * density);
        hudText.setTypeface(Typeface.MONOSPACE);
        hudBg.setColor(0xA0000000);
        hudBar.setColor(0xFFFF7A3D);
    }

    // ------------------------------------------------------------------ configuration

    public void setCallback(Callback c) {
        callback = c;
    }

    public void setScaling(String mode) {
        scaling = mode;
        requestRender();
    }

    public void setInputMode(String mode) {
        inputMode = mode;
        if (VmConfig.INPUT_MOUSE.equals(mode)) {
            requestPointerCapture();
        } else {
            releasePointerCapture();
        }
    }

    public String inputMode() {
        return inputMode;
    }

    public void setPointerSpeed(int percent) {
        pointerSpeed = Math.max(0.1f, percent / 100f);
    }

    public void setHighRefresh(boolean on) {
        highRefresh = on;
        applyFrameRate();
    }

    public void setHud(boolean visible, String[] lines, float[] loads) {
        hudVisible = visible;
        if (lines != null) hudLines = lines;
        if (loads != null) hudLoads = loads;
        requestRender();
    }

    public int takeFrameCount() {
        RfbClient r = rfb;
        return r != null ? r.takeFrameCount() : 0;
    }

    public void setMuted(boolean muted) {
        RfbClient r = rfb;
        if (r != null) r.setMuted(muted);
    }

    // ------------------------------------------------------------------ connection

    public void connect(String vncSocket, boolean audio, boolean relativePointer) {
        disconnect();
        cursor = null;
        rfb = new RfbClient(vncSocket, audio, !relativePointer, this);
        rfb.start();
    }

    public void disconnect() {
        RfbClient r = rfb;
        rfb = null;
        if (r != null) r.close();
    }

    public boolean isConnected() {
        return rfb != null;
    }

    /** Copy of the current guest screen, or null. */
    public Bitmap snapshot() {
        RfbClient r = rfb;
        if (r == null) return null;
        synchronized (r.fbLock) {
            Bitmap fb = r.framebuffer();
            return fb == null ? null : fb.copy(Bitmap.Config.ARGB_8888, false);
        }
    }

    @Override
    public void onRfbConnected(int width, int height) {
        cx = width / 2f;
        cy = height / 2f;
        post(() -> {
            if (callback != null) callback.onDisplayConnected();
        });
        requestRender();
    }

    @Override
    public void onRfbResized(int width, int height) {
        cx = Math.min(cx, width - 1);
        cy = Math.min(cy, height - 1);
        requestRender();
    }

    @Override
    public void onRfbFrame() {
        requestRender();
    }

    @Override
    public void onRfbCursor(Bitmap c, int hotX, int hotY) {
        cursor = c;
        cursorHotX = hotX;
        cursorHotY = hotY;
        requestRender();
    }

    @Override
    public void onRfbPointerMode(boolean absolute) {
        requestRender();
    }

    @Override
    public void onRfbDisconnected(String reason) {
        post(() -> {
            if (callback != null) callback.onDisplayDisconnected(reason);
        });
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        applyFrameRate();
        running = true;
        renderThread = new Thread(this::renderLoop, "vm-render");
        renderThread.start();
        requestRender();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        requestRender();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        running = false;
        synchronized (renderLock) {
            renderLock.notifyAll();
        }
        Thread t = renderThread;
        renderThread = null;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void applyFrameRate() {
        if (!surfaceReady || Build.VERSION.SDK_INT < 30) return;
        try {
            float hz = 60f;
            if (highRefresh && getDisplay() != null) {
                for (android.view.Display.Mode m : getDisplay().getSupportedModes()) {
                    hz = Math.max(hz, m.getRefreshRate());
                }
                hz = Math.min(hz, 144f);
            }
            getHolder().getSurface().setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        } catch (Exception ignored) {
            // purely a hint
        }
    }

    private void requestRender() {
        synchronized (renderLock) {
            dirty = true;
            renderLock.notifyAll();
        }
    }

    private void renderLoop() {
        while (running) {
            synchronized (renderLock) {
                while (running && !dirty) {
                    try {
                        renderLock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                dirty = false;
            }
            if (!running || !surfaceReady) continue;
            Canvas c = null;
            SurfaceHolder holder = getHolder();
            try {
                c = holder.lockHardwareCanvas();
                if (c != null) draw(c, c.getWidth(), c.getHeight());
            } catch (Exception ignored) {
                // surface went away mid-frame
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c);
                    } catch (Exception ignored) {
                        // surface went away mid-frame
                    }
                }
            }
        }
    }

    private void draw(Canvas c, int vw, int vh) {
        c.drawColor(Color.BLACK);
        RfbClient r = rfb;
        if (r != null) {
            synchronized (r.fbLock) {
                Bitmap fb = r.framebuffer();
                if (fb != null && !fb.isRecycled()) {
                    computeTransform(fb.getWidth(), fb.getHeight(), vw, vh);
                    matrix.reset();
                    matrix.setScale(scaleX, scaleY);
                    matrix.postTranslate(offX, offY);
                    c.drawBitmap(fb, matrix, bitmapPaint);
                }
            }
            Bitmap cur = cursor;
            if (cur != null && r.isAbsolute()) {
                matrix.reset();
                matrix.setScale(scaleX, scaleY);
                matrix.postTranslate(offX + (cx - cursorHotX) * scaleX,
                        offY + (cy - cursorHotY) * scaleY);
                c.drawBitmap(cur, matrix, bitmapPaint);
            }
        }
        if (hudVisible) drawHud(c);
    }

    private void computeTransform(int gw, int gh, int vw, int vh) {
        float sx;
        float sy;
        boolean smooth = true;
        if (VmConfig.SCALE_STRETCH.equals(scaling)) {
            sx = vw / (float) gw;
            sy = vh / (float) gh;
        } else {
            float fit = Math.min(vw / (float) gw, vh / (float) gh);
            if (VmConfig.SCALE_INTEGER.equals(scaling) && fit >= 1f) {
                fit = (float) Math.floor(fit);
                smooth = false;
            }
            sx = fit;
            sy = fit;
        }
        bitmapPaint.setFilterBitmap(smooth);
        scaleX = sx;
        scaleY = sy;
        offX = (vw - gw * sx) / 2f;
        offY = (vh - gh * sy) / 2f;
    }

    private void drawHud(Canvas c) {
        String[] lines = hudLines;
        float[] loads = hudLoads;
        float pad = 6 * density;
        float lh = hudText.getTextSize() * 1.25f;
        float w = 0;
        for (String s : lines) w = Math.max(w, hudText.measureText(s));
        float barW = 60 * density;
        w = Math.max(w, barW + hudText.measureText("cpu0 100%") + pad);
        float h = lh * (lines.length + loads.length) + pad * 2;
        float x = 8 * density;
        float y = 8 * density;
        c.drawRect(x, y, x + w + pad * 2, y + h, hudBg);
        float ty = y + pad + hudText.getTextSize();
        for (String s : lines) {
            c.drawText(s, x + pad, ty, hudText);
            ty += lh;
        }
        for (int i = 0; i < loads.length; i++) {
            String label = "cpu" + i + " " + Math.round(loads[i] * 100) + "%";
            c.drawText(label, x + pad, ty, hudText);
            float bx = x + pad + hudText.measureText("cpu0 100% ");
            c.drawRect(bx, ty - hudText.getTextSize() * 0.7f, bx + barW * loads[i], ty, hudBar);
            ty += lh;
        }
    }

    // ------------------------------------------------------------------ pointer plumbing

    private float guestX(float viewX) {
        return (viewX - offX) / scaleX;
    }

    private float guestY(float viewY) {
        return (viewY - offY) / scaleY;
    }

    private void sendPointer() {
        RfbClient r = rfb;
        if (r == null) return;
        if (r.isAbsolute()) {
            r.pointer(Math.round(cx), Math.round(cy), buttons);
        } else {
            r.pointerRelative(0, 0, buttons);
        }
    }

    private void moveTo(float gx, float gy) {
        RfbClient r = rfb;
        if (r == null) return;
        if (!r.isAbsolute()) {
            moveBy(gx - cx, gy - cy);
            return;
        }
        cx = Math.max(0, Math.min(r.width() - 1, gx));
        cy = Math.max(0, Math.min(r.height() - 1, gy));
        r.pointer(Math.round(cx), Math.round(cy), buttons);
        if (cursor != null) requestRender();
    }

    private void moveBy(float dx, float dy) {
        RfbClient r = rfb;
        if (r == null) return;
        if (r.isAbsolute()) {
            moveTo(cx + dx, cy + dy);
            return;
        }
        relAccX += dx;
        relAccY += dy;
        int ix = (int) relAccX;
        int iy = (int) relAccY;
        if (ix != 0 || iy != 0) {
            relAccX -= ix;
            relAccY -= iy;
            // Track a notional position so that direct-touch deltas stay meaningful.
            cx = Math.max(0, Math.min(r.width() - 1, cx + ix));
            cy = Math.max(0, Math.min(r.height() - 1, cy + iy));
            r.pointerRelative(ix, iy, buttons);
        }
    }

    private void setButton(int mask, boolean down) {
        if (down) buttons |= mask;
        else buttons &= ~mask;
        sendPointer();
    }

    private void click(int mask) {
        setButton(mask, true);
        postDelayed(() -> setButton(mask, false), 40);
    }

    private void wheel(boolean up) {
        int m = up ? BTN_WHEEL_UP : BTN_WHEEL_DOWN;
        buttons |= m;
        sendPointer();
        buttons &= ~m;
        sendPointer();
    }

    // ------------------------------------------------------------------ touch

    private final Runnable longPress = () -> {
        if (moved || maxPointers != 1) return;
        longPressFired = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        click(BTN_RIGHT);
    };

    private boolean directTouch() {
        RfbClient r = rfb;
        return VmConfig.INPUT_TOUCH.equals(inputMode) && r != null && r.isAbsolute();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (rfb == null) return true;
        if (!hasFocus()) requestFocus();
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(ev);
        boolean direct = directTouch();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = lastY = ev.getY();
                moved = false;
                dragging = false;
                longPressFired = false;
                maxPointers = 1;
                scrollAcc = 0;
                if (direct) {
                    moveTo(guestX(downX), guestY(downY));
                } else if (SystemClock.uptimeMillis() - lastTapUp < 280) {
                    tapDrag = true;
                    setButton(BTN_LEFT, true);
                }
                postDelayed(longPress, 520);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                maxPointers = Math.max(maxPointers, ev.getPointerCount());
                removeCallbacks(longPress);
                lastScrollY = averageY(ev);
                if (dragging) {
                    setButton(BTN_LEFT, false);
                    dragging = false;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() >= 2) {
                    float y = averageY(ev);
                    scrollAcc += y - lastScrollY;
                    lastScrollY = y;
                    float step = 28 * density;
                    while (Math.abs(scrollAcc) >= step) {
                        wheel(scrollAcc > 0);
                        scrollAcc -= Math.signum(scrollAcc) * step;
                        moved = true;
                    }
                } else if (maxPointers == 1) {
                    float x = ev.getX();
                    float y = ev.getY();
                    if (!moved && Math.hypot(x - downX, y - downY) > touchSlop) {
                        moved = true;
                        removeCallbacks(longPress);
                        if (direct && !longPressFired) {
                            setButton(BTN_LEFT, true);
                            dragging = true;
                        }
                    }
                    if (moved) {
                        if (direct) {
                            moveTo(guestX(x), guestY(y));
                        } else {
                            moveBy((x - lastX) * pointerSpeed / scaleX,
                                    (y - lastY) * pointerSpeed / scaleY);
                        }
                    }
                    lastX = x;
                    lastY = y;
                }
                break;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (tapDrag) {
                    setButton(BTN_LEFT, false);
                    tapDrag = false;
                } else if (dragging) {
                    setButton(BTN_LEFT, false);
                    dragging = false;
                } else if (!moved && !longPressFired) {
                    if (maxPointers == 2) {
                        click(BTN_RIGHT);
                    } else if (maxPointers == 3) {
                        click(BTN_MIDDLE);
                    } else {
                        click(BTN_LEFT);
                        lastTapUp = SystemClock.uptimeMillis();
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                tapDrag = false;
                dragging = false;
                if (buttons != 0) {
                    buttons = 0;
                    sendPointer();
                }
                break;
            default:
                break;
        }
        return true;
    }

    private static float averageY(MotionEvent ev) {
        float sum = 0;
        for (int i = 0; i < ev.getPointerCount(); i++) sum += ev.getY(i);
        return sum / ev.getPointerCount();
    }

    // ------------------------------------------------------------------ mouse

    private static int mouseButtons(MotionEvent ev) {
        int state = ev.getButtonState();
        int m = 0;
        if ((state & MotionEvent.BUTTON_PRIMARY) != 0) m |= BTN_LEFT;
        if ((state & MotionEvent.BUTTON_TERTIARY) != 0) m |= BTN_MIDDLE;
        if ((state & MotionEvent.BUTTON_SECONDARY) != 0) m |= BTN_RIGHT;
        return m;
    }

    private boolean onMouse(MotionEvent ev) {
        RfbClient r = rfb;
        if (r == null) return true;
        if (ev.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v != 0) wheel(v > 0);
            return true;
        }
        buttons = mouseButtons(ev);
        if (r.isAbsolute()) {
            moveTo(guestX(ev.getX()), guestY(ev.getY()));
        } else {
            moveBy((ev.getX() - lastX) / scaleX, (ev.getY() - lastY) / scaleY);
            sendPointer();
        }
        lastX = ev.getX();
        lastY = ev.getY();
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (rfb != null && ev.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(ev);
        return super.onGenericMotionEvent(ev);
    }

    @Override
    public boolean onCapturedPointerEvent(MotionEvent ev) {
        if (rfb == null) return true;
        if (ev.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v != 0) wheel(v > 0);
            return true;
        }
        int b = mouseButtons(ev);
        boolean changed = b != buttons;
        buttons = b;
        float dx = ev.getX() * pointerSpeed;
        float dy = ev.getY() * pointerSpeed;
        if (dx != 0 || dy != 0) moveBy(dx, dy);
        else if (changed) sendPointer();
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && VmConfig.INPUT_MOUSE.equals(inputMode)) requestPointerCapture();
    }

    // ------------------------------------------------------------------ keyboard

    /** Handles a hardware or IME key event; returns false if it is not a guest key. */
    public boolean handleKey(KeyEvent event) {
        RfbClient r = rfb;
        if (r == null) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return false;
        }
        int q = Keymap.qnum(code);
        if (q == 0) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!Keymap.isModifier(q)) pressSticky(r, true);
            r.key(q, true);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            r.key(q, false);
            if (!Keymap.isModifier(q)) releaseSticky(r);
        }
        return true;
    }

    private void pressSticky(RfbClient r, boolean down) {
        for (int m : sticky) r.key(m, down);
    }

    private void releaseSticky(RfbClient r) {
        if (sticky.isEmpty()) return;
        pressSticky(r, false);
        sticky.clear();
        if (callback != null) callback.onStickyKeysChanged(new HashSet<>(sticky));
    }

    /** Toggles a modifier from the extra-keys bar; it applies to the next key press. */
    public void toggleSticky(int qnum) {
        if (!sticky.remove(qnum)) sticky.add(qnum);
        if (callback != null) callback.onStickyKeysChanged(new HashSet<>(sticky));
    }

    /** Presses and releases one key, together with any armed sticky modifiers. */
    public void tapKey(int qnum) {
        RfbClient r = rfb;
        if (r == null) return;
        pressSticky(r, true);
        r.key(qnum, true);
        r.key(qnum, false);
        releaseSticky(r);
    }

    /** Presses a chord such as Ctrl+Alt+Del. */
    public void chord(int... qnums) {
        RfbClient r = rfb;
        if (r == null) return;
        for (int q : qnums) r.key(q, true);
        for (int i = qnums.length - 1; i >= 0; i--) r.key(qnums[i], false);
    }

    /** Types text using a US keyboard layout in the guest. */
    public void typeText(CharSequence text) {
        RfbClient r = rfb;
        if (r == null || text == null) return;
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                tapKey(Keymap.ENTER);
                continue;
            }
            KeyEvent[] events = kcm.getEvents(new char[]{ch});
            if (events == null) continue;
            boolean first = true;
            for (KeyEvent e : events) {
                int q = Keymap.qnum(e.getKeyCode());
                if (q == 0) continue;
                boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
                if (first && down && !Keymap.isModifier(q)) pressSticky(r, true);
                first = false;
                r.key(q, down);
            }
            releaseSticky(r);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                typeText(text);
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < Math.max(1, beforeLength); i++) tapKey(Keymap.BACKSPACE);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                return handleKey(event);
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                tapKey(Keymap.ENTER);
                return true;
            }
        };
    }
}
