package com.ccs.maple.display;

import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import com.ccs.maple.qemu.NativeBridge;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RFB (VNC) client for QEMU's built-in display server, connected over a unix socket.
 * Implements raw rectangles, desktop resize, rich cursors and the QEMU extensions for
 * scancode key events, relative pointer mode and guest audio.
 */
public final class RfbClient {

    public interface Listener {
        void onRfbConnected(int width, int height);

        void onRfbResized(int width, int height);

        void onRfbFrame();

        void onRfbCursor(Bitmap cursor, int hotX, int hotY);

        void onRfbPointerMode(boolean absolute);

        void onRfbDisconnected(String reason);
    }

    private static final int ENC_RAW = 0;
    private static final int ENC_DESKTOP_SIZE = -223;
    private static final int ENC_RICH_CURSOR = -239;
    private static final int ENC_POINTER_TYPE = -257;
    private static final int ENC_EXT_KEY = -258;
    private static final int ENC_AUDIO = -259;

    private static final int AUDIO_RATE = 44100;

    private final String socketPath;
    private final Listener listener;
    private final boolean wantAudio;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rfb-send");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger frames = new AtomicInteger();

    /** Guards {@link #framebuffer}; hold it while drawing or replacing the bitmap. */
    public final Object fbLock = new Object();
    private Bitmap framebuffer;

    private volatile boolean closed;
    private volatile boolean extKeys;
    private volatile boolean absolute = true;
    private volatile boolean muted;
    private LocalSocket socket;
    private OutputStream out;
    private AudioTrack audio;
    private int width;
    private int height;

    public RfbClient(String socketPath, boolean audio, boolean absoluteDefault, Listener l) {
        this.socketPath = socketPath;
        this.wantAudio = audio;
        this.absolute = absoluteDefault;
        this.listener = l;
    }

    public void start() {
        Thread t = new Thread(this::run, "rfb-read");
        t.setDaemon(true);
        t.start();
    }

    public Bitmap framebuffer() {
        return framebuffer;
    }

    public boolean isAbsolute() {
        return absolute;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Frames received since the previous call. */
    public int takeFrameCount() {
        return frames.getAndSet(0);
    }

    public void setMuted(boolean m) {
        muted = m;
    }

    public void close() {
        closed = true;
        sender.shutdownNow();
        LocalSocket s = socket;
        if (s != null) {
            try {
                s.shutdownInput();
            } catch (IOException ignored) {
                // already closed
            }
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    // ------------------------------------------------------------------ protocol

    private void run() {
        String reason = null;
        try {
            connect();
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream(), 1 << 16));
            handshake(in);
            listener.onRfbConnected(width, height);
            requestUpdate(false);
            readLoop(in);
        } catch (IOException e) {
            reason = e.getMessage();
        } finally {
            AudioTrack a = audio;
            audio = null;
            if (a != null) {
                try {
                    a.release();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
            if (!closed) listener.onRfbDisconnected(reason);
            close();
        }
    }

    private void connect() throws IOException {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        IOException last = null;
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            if (new File(socketPath).exists()) {
                LocalSocket s = new LocalSocket();
                try {
                    s.connect(new LocalSocketAddress(socketPath,
                            LocalSocketAddress.Namespace.FILESYSTEM));
                    socket = s;
                    out = s.getOutputStream();
                    return;
                } catch (IOException e) {
                    last = e;
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // nothing to do
                    }
                }
            }
            SystemClock.sleep(100);
        }
        throw last != null ? last : new IOException("Display socket did not appear");
    }

    private void handshake(DataInputStream in) throws IOException {
        byte[] ver = new byte[12];
        in.readFully(ver);
        if (ver[0] != 'R' || ver[1] != 'F' || ver[2] != 'B') throw new IOException("Not RFB");
        out.write("RFB 003.008\n".getBytes());
        int n = in.readUnsignedByte();
        if (n == 0) throw new IOException("Server refused connection");
        byte[] types = new byte[n];
        in.readFully(types);
        boolean none = false;
        for (byte t : types) {
            if (t == 1) none = true;
        }
        if (!none) throw new IOException("Server requires authentication");
        out.write(1);
        if (in.readInt() != 0) throw new IOException("Security handshake failed");
        out.write(1); // shared
        out.flush();

        width = in.readUnsignedShort();
        height = in.readUnsignedShort();
        in.skipBytes(16);
        int nameLen = in.readInt();
        in.skipBytes(nameLen);
        allocate(width, height);

        ByteBuffer b = ByteBuffer.allocate(20 + 4 + 6 * 4);
        // SetPixelFormat: 32 bpp, little endian, bytes on the wire are R,G,B,X.
        b.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        b.put((byte) 32).put((byte) 24).put((byte) 0).put((byte) 1);
        b.putShort((short) 255).putShort((short) 255).putShort((short) 255);
        b.put((byte) 0).put((byte) 8).put((byte) 16);
        b.put((byte) 0).put((byte) 0).put((byte) 0);
        // SetEncodings
        b.put((byte) 2).put((byte) 0).putShort((short) 6);
        b.putInt(ENC_RAW).putInt(ENC_DESKTOP_SIZE).putInt(ENC_RICH_CURSOR)
                .putInt(ENC_POINTER_TYPE).putInt(ENC_EXT_KEY).putInt(ENC_AUDIO);
        out.write(b.array());
        out.flush();
    }

    private void allocate(int w, int h) {
        synchronized (fbLock) {
            width = Math.max(1, w);
            height = Math.max(1, h);
            Bitmap old = framebuffer;
            framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (old != null) old.recycle();
        }
    }

    private void readLoop(DataInputStream in) throws IOException {
        byte[] buf = new byte[1 << 20];
        while (!closed) {
            int type = in.readUnsignedByte();
            switch (type) {
                case 0: { // FramebufferUpdate
                    in.skipBytes(1);
                    int rects = in.readUnsignedShort();
                    boolean drew = false;
                    for (int i = 0; i < rects; i++) {
                        int x = in.readUnsignedShort();
                        int y = in.readUnsignedShort();
                        int w = in.readUnsignedShort();
                        int h = in.readUnsignedShort();
                        int enc = in.readInt();
                        switch (enc) {
                            case ENC_RAW: {
                                int len = w * h * 4;
                                if (len > buf.length) buf = new byte[len];
                                in.readFully(buf, 0, len);
                                synchronized (fbLock) {
                                    if (framebuffer != null && w > 0 && h > 0) {
                                        NativeBridge.blit(framebuffer, buf, x, y, w, h);
                                    }
                                }
                                drew = true;
                                break;
                            }
                            case ENC_DESKTOP_SIZE:
                                allocate(w, h);
                                listener.onRfbResized(width, height);
                                break;
                            case ENC_RICH_CURSOR: {
                                int len = w * h * 4;
                                int maskRow = (w + 7) / 8;
                                byte[] px = new byte[len];
                                byte[] mask = new byte[maskRow * h];
                                in.readFully(px);
                                in.readFully(mask);
                                listener.onRfbCursor(decodeCursor(px, mask, w, h), x, y);
                                break;
                            }
                            case ENC_POINTER_TYPE:
                                absolute = x != 0;
                                listener.onRfbPointerMode(absolute);
                                break;
                            case ENC_EXT_KEY:
                                extKeys = true;
                                break;
                            case ENC_AUDIO:
                                if (wantAudio) enableAudio();
                                break;
                            default:
                                throw new IOException("Unsupported encoding " + enc);
                        }
                    }
                    if (drew) {
                        frames.incrementAndGet();
                        listener.onRfbFrame();
                    }
                    requestUpdate(true);
                    break;
                }
                case 1: { // SetColourMapEntries
                    in.skipBytes(3);
                    int n = in.readUnsignedShort();
                    in.skipBytes(n * 6);
                    break;
                }
                case 2: // Bell
                    break;
                case 3: { // ServerCutText
                    in.skipBytes(3);
                    int len = in.readInt();
                    in.skipBytes(len);
                    break;
                }
                case 255: { // QEMU
                    int sub = in.readUnsignedByte();
                    if (sub != 1) throw new IOException("Unknown QEMU message " + sub);
                    int op = in.readUnsignedShort();
                    if (op == 2) {
                        int len = in.readInt();
                        if (len > buf.length) buf = new byte[len];
                        in.readFully(buf, 0, len);
                        AudioTrack a = audio;
                        if (a != null && !muted) {
                            a.write(buf, 0, len, AudioTrack.WRITE_NON_BLOCKING);
                        }
                    } else if (op == 1) {
                        AudioTrack a = audio;
                        if (a != null) a.play();
                    }
                    break;
                }
                default:
                    throw new IOException("Unknown server message " + type);
            }
        }
    }

    private static Bitmap decodeCursor(byte[] px, byte[] mask, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        int[] argb = new int[w * h];
        int maskRow = (w + 7) / 8;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean on = (mask[y * maskRow + x / 8] & (0x80 >> (x % 8))) != 0;
                if (!on) continue;
                int o = (y * w + x) * 4;
                argb[y * w + x] = 0xFF000000 | ((px[o] & 0xFF) << 16) | ((px[o + 1] & 0xFF) << 8)
                        | (px[o + 2] & 0xFF);
            }
        }
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
    }

    private void enableAudio() {
        try {
            int min = AudioTrack.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            audio = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(AUDIO_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min * 4, AUDIO_RATE * 4 / 5))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            audio.play();
        } catch (Exception e) {
            audio = null;
            return;
        }
        ByteBuffer b = ByteBuffer.allocate(10 + 4);
        // Set format: signed 16 bit, stereo, 44.1 kHz. Then enable capture.
        b.put((byte) 255).put((byte) 1).putShort((short) 2);
        b.put((byte) 3).put((byte) 2).putInt(AUDIO_RATE);
        b.put((byte) 255).put((byte) 1).putShort((short) 0);
        send(b.array());
    }

    // ------------------------------------------------------------------ client messages

    private void send(byte[] data) {
        if (closed) return;
        try {
            sender.execute(() -> {
                try {
                    OutputStream o = out;
                    if (o != null) {
                        o.write(data);
                        o.flush();
                    }
                } catch (IOException ignored) {
                    // the read loop notices the broken connection
                }
            });
        } catch (Exception ignored) {
            // executor shut down
        }
    }

    private void requestUpdate(boolean incremental) {
        ByteBuffer b = ByteBuffer.allocate(10);
        b.put((byte) 3).put((byte) (incremental ? 1 : 0));
        b.putShort((short) 0).putShort((short) 0);
        b.putShort((short) width).putShort((short) height);
        send(b.array());
    }

    /**
     * Sends a key by PC scancode ("qnum": set 1 make code, with 0x80 set for E0-prefixed keys).
     */
    public void key(int qnum, boolean down) {
        if (qnum <= 0) return;
        if (extKeys) {
            ByteBuffer b = ByteBuffer.allocate(12);
            b.put((byte) 255).put((byte) 0).putShort((short) (down ? 1 : 0));
            b.putInt(0).putInt(qnum);
            send(b.array());
        } else {
            int sym = Keymap.keysymForQnum(qnum);
            if (sym == 0) return;
            ByteBuffer b = ByteBuffer.allocate(8);
            b.put((byte) 4).put((byte) (down ? 1 : 0)).putShort((short) 0).putInt(sym);
            send(b.array());
        }
    }

    /** Absolute pointer event in guest pixels. */
    public void pointer(int x, int y, int buttons) {
        ByteBuffer b = ByteBuffer.allocate(6);
        b.put((byte) 5).put((byte) buttons);
        b.putShort((short) Math.max(0, Math.min(width - 1, x)));
        b.putShort((short) Math.max(0, Math.min(height - 1, y)));
        send(b.array());
    }

    /** Relative pointer event (only meaningful while the server is in relative mode). */
    public void pointerRelative(int dx, int dy, int buttons) {
        ByteBuffer b = ByteBuffer.allocate(6);
        b.put((byte) 5).put((byte) buttons);
        b.putShort((short) (0x7FFF + Math.max(-0x7FFF, Math.min(0x7FFF, dx))));
        b.putShort((short) (0x7FFF + Math.max(-0x7FFF, Math.min(0x7FFF, dy))));
        send(b.array());
    }
}
