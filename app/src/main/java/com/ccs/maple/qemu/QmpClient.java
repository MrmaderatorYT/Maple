package com.ccs.maple.qemu;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Minimal QEMU Machine Protocol client over a unix socket. */
public final class QmpClient implements Closeable {

    public interface EventListener {
        void onQmpEvent(String name, JSONObject data);
    }

    private final LocalSocket socket = new LocalSocket();
    private final LinkedBlockingQueue<JSONObject> replies = new LinkedBlockingQueue<>();
    private OutputStream out;
    private volatile boolean closed;
    private volatile EventListener listener;

    public void setListener(EventListener l) {
        listener = l;
    }

    /** Connects and negotiates capabilities. The socket must already exist. */
    public void connect(String path) throws IOException {
        socket.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
        out = socket.getOutputStream();
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String greeting = in.readLine();
        if (greeting == null || !greeting.contains("QMP")) {
            throw new IOException("Unexpected QMP greeting: " + greeting);
        }
        Thread t = new Thread(() -> readLoop(in), "qmp-reader");
        t.setDaemon(true);
        t.start();
        execute("qmp_capabilities", null, 5000);
    }

    private void readLoop(BufferedReader in) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                JSONObject o;
                try {
                    o = new JSONObject(line);
                } catch (JSONException e) {
                    continue;
                }
                if (o.has("event")) {
                    EventListener l = listener;
                    if (l != null) l.onQmpEvent(o.optString("event"), o.optJSONObject("data"));
                } else if (o.has("return") || o.has("error")) {
                    replies.offer(o);
                }
            }
        } catch (IOException ignored) {
            // socket closed
        }
        closed = true;
        replies.offer(new JSONObject()); // wake a waiting caller
    }

    /**
     * Runs one command. Returns the reply object (containing "return").
     *
     * @throws IOException on transport failure, timeout, or a QMP error reply
     */
    public synchronized JSONObject execute(String command, JSONObject args, long timeoutMs)
            throws IOException {
        if (closed) throw new IOException("QMP connection closed");
        try {
            JSONObject req = new JSONObject();
            req.put("execute", command);
            if (args != null) req.put("arguments", args);
            replies.clear();
            out.write((req + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            JSONObject reply = replies.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (reply == null) throw new IOException("QMP timeout: " + command);
            if (reply.has("error")) {
                JSONObject err = reply.optJSONObject("error");
                throw new IOException(err != null ? err.optString("desc", "QMP error")
                        : "QMP error");
            }
            if (!reply.has("return")) throw new IOException("QMP connection closed");
            return reply;
        } catch (JSONException e) {
            throw new IOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    public JSONObject execute(String command) throws IOException {
        return execute(command, null, 10000);
    }

    /** Runs a human-monitor command and returns its text output. */
    public String hmp(String commandLine, long timeoutMs) throws IOException {
        try {
            JSONObject args = new JSONObject();
            args.put("command-line", commandLine);
            return execute("human-monitor-command", args, timeoutMs).optString("return", "");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.shutdownInput();
        } catch (IOException ignored) {
            // already closed
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
