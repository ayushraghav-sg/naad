package com.example.voicerecorder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

/**
 * Streams the recorder's audio live to an RTMP / RTMPS server as AAC.
 * Self-contained: AAC from Android's encoder, a minimal RTMP publisher written here.
 * If the connection drops it keeps retrying while recording continues.
 */
public class RtmpStreamer {

    public interface StateListener { void onStreamState(String state, String detail); }

    public static final String CONNECTING = "connecting", LIVE = "live", RETRYING = "retrying",
            FAILED = "failed", STOPPED = "stopped";

    private static final int RATE = 44100;
    private static final int OUT_CHUNK = 4096;

    private final String tcUrl, host, app, streamName;
    private final int port, kbps;
    private final boolean tls;
    private final StateListener listener;
    private final BlockingQueue<short[]> queue = new ArrayBlockingQueue<>(200);
    private volatile boolean running = true;
    private Thread worker;

    private volatile String state = STOPPED;

    // connection state
    private Socket socket;
    private OutputStream out;
    private final Object writeLock = new Object();
    private volatile int streamId = -1;
    private volatile boolean published;
    private volatile String error;
    private long bytesRead, lastAck, windowSize = 2500000;

    public RtmpStreamer(String url, String key, int kbps, StateListener l) throws IllegalArgumentException {
        this.kbps = kbps;
        this.listener = l;
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        String k = key == null ? "" : key.trim();
        URI uri = URI.create(u);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("rtmp") && !scheme.equals("rtmps"))
            throw new IllegalArgumentException("Server URL must start with rtmp:// or rtmps://");
        tls = scheme.equals("rtmps");
        host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("Server URL has no host");
        port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 1935);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (k.isEmpty()) { // key included at the end of the URL
            int i = path.lastIndexOf('/');
            if (i < 0) throw new IllegalArgumentException("Add a stream key");
            k = path.substring(i + 1);
            path = path.substring(0, i);
        }
        if (path.isEmpty()) throw new IllegalArgumentException("Server URL needs an app name, e.g. rtmp://server/live");
        app = path;
        streamName = k;
        tcUrl = scheme + "://" + host + (uri.getPort() > 0 ? ":" + port : "") + "/" + app;
    }

    public String state() { return state; }

    public void start() {
        worker = new Thread(this::loop, "rtmp-stream");
        worker.start();
    }

    /** Called from the recording thread with mono 44.1 kHz PCM. */
    public void feed(short[] pcm, int n) {
        if (!running) return;
        short[] c = new short[n];
        System.arraycopy(pcm, 0, c, 0, n);
        if (!queue.offer(c)) { queue.poll(); queue.offer(c); } // drop oldest if the network is slow
    }

    public void stop() {
        running = false;
        queue.offer(new short[0]);
        closeSocket();
        if (worker != null) try { worker.join(3000); } catch (InterruptedException ignored) { }
        setState(STOPPED, null);
    }

    private void setState(String s, String detail) {
        state = s;
        if (listener != null) listener.onStreamState(s, detail);
    }

    // ---------- main loop with reconnects ----------
    private void loop() {
        int attempt = 0;
        while (running) {
            setState(attempt == 0 ? CONNECTING : RETRYING, attempt == 0 ? null : error);
            try {
                session();
                attempt = 0;
            } catch (Exception e) {
                if (!running) break;
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                if (error.startsWith("REJECTED:")) {
                    setState(FAILED, error.substring(9));
                    running = false;
                    break;
                }
                attempt++;
                setState(RETRYING, error);
                long wait = Math.min(15000, 2000L * attempt);
                long end = SystemClock.elapsedRealtime() + wait;
                while (running && SystemClock.elapsedRealtime() < end) {
                    queue.poll(); // discard audio while offline
                    SystemClock.sleep(100);
                }
            } finally {
                closeSocket();
            }
        }
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        socket = null;
    }

    private void session() throws Exception {
        streamId = -1;
        published = false;
        bytesRead = 0;
        lastAck = 0;
        Socket s = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        s.connect(new InetSocketAddress(host, port), 10000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(0);
        socket = s;
        out = new BufferedOutputStream(s.getOutputStream(), 16 * 1024);
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));

        handshake(in);
        Thread reader = new Thread(() -> readLoop(in), "rtmp-read");
        reader.setDaemon(true);
        reader.start();

        sendSetChunkSize(OUT_CHUNK);
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("app", app);
        obj.put("type", "nonprivate");
        obj.put("flashVer", "FMLE/3.0 (compatible; Naad)");
        obj.put("tcUrl", tcUrl);
        sendCommand(3, 0, "connect", 1, obj);
        sendCommand(3, 0, "releaseStream", 2, null, streamName);
        sendCommand(3, 0, "FCPublish", 3, null, streamName);
        sendCommand(3, 0, "createStream", 4, (Object) null);

        long until = SystemClock.elapsedRealtime() + 10000;
        while (streamId < 0) {
            checkError();
            if (SystemClock.elapsedRealtime() > until) throw new IOException("Server did not answer");
            SystemClock.sleep(20);
        }
        sendCommand(8, streamId, "publish", 5, null, streamName, "live");
        until = SystemClock.elapsedRealtime() + 10000;
        while (!published) {
            checkError();
            if (SystemClock.elapsedRealtime() > until) break; // some servers never confirm; carry on
            SystemClock.sleep(20);
        }
        sendMetadata();
        setState(LIVE, null);
        streamAudio();
    }

    private void checkError() throws IOException {
        String e = error;
        if (e != null && (e.startsWith("REJECTED:") || e.startsWith("CLOSED"))) throw new IOException(e);
        if (socket == null || socket.isClosed()) throw new IOException("Connection closed");
    }

    private void handshake(DataInputStream in) throws IOException {
        byte[] c1 = new byte[1536];
        new Random().nextBytes(c1);
        int t = (int) (SystemClock.elapsedRealtime() & 0x7fffffff);
        c1[0] = (byte) (t >>> 24); c1[1] = (byte) (t >>> 16); c1[2] = (byte) (t >>> 8); c1[3] = (byte) t;
        c1[4] = c1[5] = c1[6] = c1[7] = 0;
        synchronized (writeLock) {
            out.write(3);
            out.write(c1);
            out.flush();
        }
        int s0 = in.read();
        if (s0 != 3) throw new IOException("Not an RTMP server");
        byte[] s1 = new byte[1536];
        in.readFully(s1);
        synchronized (writeLock) {
            out.write(s1); // C2 echoes S1
            out.flush();
        }
        byte[] s2 = new byte[1536];
        in.readFully(s2);
    }

    // ---------- audio ----------
    private void streamAudio() throws Exception {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long samples = 0;
        boolean headerSent = false;
        queue.clear();
        try {
            while (running) {
                checkError();
                short[] pcm = queue.poll(200, TimeUnit.MILLISECONDS);
                if (pcm != null && pcm.length == 0) break;
                if (pcm == null) pcm = new short[RATE / 5]; // paused or slow: send 200 ms of silence
                if (pcm != null) {
                    int off = 0;
                    while (off < pcm.length) {
                        int ii = enc.dequeueInputBuffer(10000);
                        if (ii < 0) break;
                        ByteBuffer b = enc.getInputBuffer(ii);
                        b.clear();
                        int count = Math.min(pcm.length - off, b.remaining() / 2);
                        for (int i = 0; i < count; i++) {
                            short v = pcm[off + i];
                            b.put((byte) v);
                            b.put((byte) (v >> 8));
                        }
                        enc.queueInputBuffer(ii, 0, count * 2, samples * 1000000L / RATE, 0);
                        samples += count;
                        off += count;
                    }
                }
                int oi;
                while ((oi = enc.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        ByteBuffer csd = enc.getOutputFormat().getByteBuffer("csd-0");
                        if (csd != null && !headerSent) { sendAacHeader(csd); headerSent = true; }
                        continue;
                    }
                    if (oi < 0) continue;
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        byte[] frame = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(frame);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            if (!headerSent) { sendAacHeader(ByteBuffer.wrap(frame)); headerSent = true; }
                        } else if (headerSent) {
                            sendAudio(frame, (int) (info.presentationTimeUs / 1000));
                        }
                    }
                    enc.releaseOutputBuffer(oi, false);
                }
            }
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
        }
        if (!running) {
            try {
                sendCommand(3, 0, "FCUnpublish", 6, null, streamName);
                sendCommand(3, 0, "deleteStream", 7, null, (double) streamId);
            } catch (Exception ignored) { }
        }
    }

    private void sendAacHeader(ByteBuffer csd) throws IOException {
        byte[] asc = new byte[csd.remaining()];
        csd.get(asc);
        byte[] tag = new byte[2 + asc.length];
        tag[0] = (byte) 0xAF; // AAC, 44 kHz, 16-bit, (stereo flag is always set for AAC)
        tag[1] = 0;           // sequence header
        System.arraycopy(asc, 0, tag, 2, asc.length);
        writeMessage(4, 8, 0, streamId, tag);
    }

    private void sendAudio(byte[] frame, int ts) throws IOException {
        byte[] tag = new byte[2 + frame.length];
        tag[0] = (byte) 0xAF;
        tag[1] = 1; // raw AAC
        System.arraycopy(frame, 0, tag, 2, frame.length);
        writeMessage(4, 8, ts, streamId, tag);
    }

    private void sendMetadata() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        amfString(b, "@setDataFrame");
        amfString(b, "onMetaData");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("duration", 0.0);
        m.put("audiocodecid", 10.0);
        m.put("audiodatarate", (double) kbps);
        m.put("audiosamplerate", (double) RATE);
        m.put("audiosamplesize", 16.0);
        m.put("stereo", false);
        m.put("encoder", "Naad");
        b.write(0x08); // ECMA array
        writeInt(b, m.size());
        for (Map.Entry<String, Object> e : m.entrySet()) {
            amfKey(b, e.getKey());
            amfValue(b, e.getValue());
        }
        b.write(0); b.write(0); b.write(9);
        writeMessage(4, 18, 0, streamId, b.toByteArray());
    }

    // ---------- RTMP messages ----------
    private void sendSetChunkSize(int size) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeInt(b, size);
        writeMessage(2, 1, 0, 0, b.toByteArray());
    }

    private void sendCommand(int csid, int msid, String name, double txn, Object... args) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        amfString(b, name);
        amfValue(b, txn);
        if (args != null) for (Object a : args) amfValue(b, a);
        writeMessage(csid, 20, 0, msid, b.toByteArray());
    }

    private void writeMessage(int csid, int type, int ts, int msid, byte[] payload) throws IOException {
        synchronized (writeLock) {
            OutputStream o = out;
            if (o == null) throw new IOException("Not connected");
            boolean ext = ts >= 0xFFFFFF;
            o.write(csid & 0x3F); // fmt 0
            int t = ext ? 0xFFFFFF : ts;
            o.write(t >>> 16); o.write(t >>> 8); o.write(t);
            int len = payload.length;
            o.write(len >>> 16); o.write(len >>> 8); o.write(len);
            o.write(type);
            o.write(msid); o.write(msid >>> 8); o.write(msid >>> 16); o.write(msid >>> 24); // little endian
            if (ext) { o.write(ts >>> 24); o.write(ts >>> 16); o.write(ts >>> 8); o.write(ts); }
            int off = 0;
            while (off < len) {
                if (off > 0) {
                    o.write(0xC0 | (csid & 0x3F)); // fmt 3 continuation
                    if (ext) { o.write(ts >>> 24); o.write(ts >>> 16); o.write(ts >>> 8); o.write(ts); }
                }
                int n = Math.min(OUT_CHUNK, len - off);
                o.write(payload, off, n);
                off += n;
            }
            o.flush();
        }
    }

    // ---------- reading the server ----------
    private static final class ChunkState {
        int ts, len, type, msid;
        ByteArrayOutputStream buf;
    }

    private void readLoop(DataInputStream in) {
        Map<Integer, ChunkState> states = new HashMap<>();
        int inChunk = 128;
        try {
            while (running && socket != null) {
                int b0 = in.readUnsignedByte();
                count(1);
                int fmt = b0 >>> 6;
                int csid = b0 & 0x3F;
                if (csid == 0) { csid = 64 + in.readUnsignedByte(); count(1); }
                else if (csid == 1) { csid = 64 + in.readUnsignedByte() + in.readUnsignedByte() * 256; count(2); }
                ChunkState st = states.get(csid);
                if (st == null) { st = new ChunkState(); states.put(csid, st); }
                if (fmt <= 2) {
                    int ts = read24(in);
                    count(3);
                    if (fmt <= 1) {
                        st.len = read24(in);
                        st.type = in.readUnsignedByte();
                        count(4);
                        if (fmt == 0) {
                            st.msid = Integer.reverseBytes(in.readInt());
                            count(4);
                        }
                    }
                    if (ts == 0xFFFFFF) { ts = in.readInt(); count(4); }
                    st.ts = ts;
                } else if (st.ts >= 0xFFFFFF && (st.buf == null || st.buf.size() == 0)) {
                    in.readInt();
                    count(4);
                }
                if (st.buf == null) st.buf = new ByteArrayOutputStream();
                int need = Math.min(inChunk, st.len - st.buf.size());
                byte[] data = new byte[Math.max(0, need)];
                in.readFully(data);
                count(data.length);
                st.buf.write(data);
                if (st.buf.size() >= st.len) {
                    byte[] msg = st.buf.toByteArray();
                    st.buf.reset();
                    int r = handleMessage(st.type, msg);
                    if (r > 0) inChunk = r;
                }
            }
        } catch (Exception e) {
            if (running && error == null) error = "CLOSED: connection lost";
            closeSocket();
        }
    }

    private void count(int n) {
        bytesRead += n;
        if (bytesRead - lastAck > windowSize / 2) {
            lastAck = bytesRead;
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeInt(b, (int) bytesRead);
                writeMessage(2, 3, 0, 0, b.toByteArray());
            } catch (Exception ignored) { }
        }
    }

    /** Returns a new incoming chunk size, or 0. */
    private int handleMessage(int type, byte[] m) throws IOException {
        switch (type) {
            case 1: return readInt(m, 0) & 0x7fffffff;
            case 5: windowSize = readInt(m, 0) & 0xffffffffL; return 0;
            case 6: { // set peer bandwidth -> answer with window ack size
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeInt(b, (int) windowSize);
                writeMessage(2, 5, 0, 0, b.toByteArray());
                return 0;
            }
            case 4: { // user control: ping request -> pong
                if (m.length >= 6 && ((m[0] << 8) | (m[1] & 0xff)) == 6) {
                    byte[] pong = new byte[6];
                    pong[1] = 7;
                    System.arraycopy(m, 2, pong, 2, 4);
                    writeMessage(2, 4, 0, 0, pong);
                }
                return 0;
            }
            case 20: handleCommand(m); return 0;
            default: return 0;
        }
    }

    private void handleCommand(byte[] m) {
        List<Object> v = new ArrayList<>();
        int[] pos = {0};
        try { while (pos[0] < m.length) v.add(amfRead(m, pos)); } catch (Exception ignored) { }
        if (v.isEmpty() || !(v.get(0) instanceof String)) return;
        String name = (String) v.get(0);
        double txn = v.size() > 1 && v.get(1) instanceof Double ? (Double) v.get(1) : 0;
        if ("_result".equals(name) && txn == 4) {
            for (int i = 2; i < v.size(); i++)
                if (v.get(i) instanceof Double) { streamId = ((Double) v.get(i)).intValue(); break; }
        } else if ("_error".equals(name)) {
            String desc = describe(v);
            if (txn == 1) error = "REJECTED:Server refused the connection" + (desc.isEmpty() ? "" : ": " + desc);
            else if (txn == 4) error = "REJECTED:Server refused the stream" + (desc.isEmpty() ? "" : ": " + desc);
        } else if ("onStatus".equals(name)) {
            String code = "";
            for (Object o : v) if (o instanceof Map) { Object c = ((Map<?, ?>) o).get("code"); if (c != null) code = c.toString(); }
            if (code.contains("Publish.Start")) published = true;
            else if (code.contains("BadName") || code.contains("Rejected") || code.contains("Publish.Denied"))
                error = "REJECTED:Stream key was rejected (" + code + ")";
            else if (code.contains("Failed") || code.contains("Error"))
                error = "CLOSED: " + code;
        }
    }

    private static String describe(List<Object> v) {
        for (Object o : v) if (o instanceof Map) {
            Object d = ((Map<?, ?>) o).get("description");
            if (d != null) return d.toString();
            Object c = ((Map<?, ?>) o).get("code");
            if (c != null) return c.toString();
        }
        return "";
    }

    // ---------- AMF0 ----------
    private static void amfString(ByteArrayOutputStream b, String s) {
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        b.write(0x02);
        b.write(d.length >>> 8); b.write(d.length);
        b.write(d, 0, d.length);
    }

    private static void amfKey(ByteArrayOutputStream b, String s) {
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        b.write(d.length >>> 8); b.write(d.length);
        b.write(d, 0, d.length);
    }

    @SuppressWarnings("unchecked")
    private static void amfValue(ByteArrayOutputStream b, Object v) {
        if (v == null) { b.write(0x05); return; }
        if (v instanceof String) { amfString(b, (String) v); return; }
        if (v instanceof Boolean) { b.write(0x01); b.write((Boolean) v ? 1 : 0); return; }
        if (v instanceof Number) {
            b.write(0x00);
            long bits = Double.doubleToLongBits(((Number) v).doubleValue());
            for (int i = 7; i >= 0; i--) b.write((int) (bits >>> (i * 8)));
            return;
        }
        if (v instanceof Map) {
            b.write(0x03);
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                amfKey(b, e.getKey());
                amfValue(b, e.getValue());
            }
            b.write(0); b.write(0); b.write(9);
        }
    }

    private static Object amfRead(byte[] m, int[] p) {
        int t = m[p[0]++] & 0xff;
        switch (t) {
            case 0x00: { long bits = 0; for (int i = 0; i < 8; i++) bits = (bits << 8) | (m[p[0]++] & 0xff); return Double.longBitsToDouble(bits); }
            case 0x01: return m[p[0]++] != 0;
            case 0x02: return readUtf(m, p, ((m[p[0]++] & 0xff) << 8) | (m[p[0]++] & 0xff));
            case 0x0C: return readUtf(m, p, readInt(m, (p[0] += 4) - 4));
            case 0x03: return readObject(m, p);
            case 0x08: p[0] += 4; return readObject(m, p);
            case 0x0A: { int n = readInt(m, p[0]); p[0] += 4; List<Object> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(amfRead(m, p)); return l; }
            case 0x0B: p[0] += 10; return null;
            default: return null; // null, undefined
        }
    }

    private static Map<String, Object> readObject(byte[] m, int[] p) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (p[0] + 2 < m.length) {
            int kl = ((m[p[0]] & 0xff) << 8) | (m[p[0] + 1] & 0xff);
            p[0] += 2;
            if (kl == 0 && (m[p[0]] & 0xff) == 0x09) { p[0]++; break; }
            String k = readUtf(m, p, kl);
            map.put(k, amfRead(m, p));
        }
        return map;
    }

    private static String readUtf(byte[] m, int[] p, int len) {
        String s = new String(m, p[0], len, StandardCharsets.UTF_8);
        p[0] += len;
        return s;
    }

    private static int read24(InputStream in) throws IOException {
        return (in.read() << 16) | (in.read() << 8) | in.read();
    }

    private static int readInt(byte[] m, int o) {
        return ((m[o] & 0xff) << 24) | ((m[o + 1] & 0xff) << 16) | ((m[o + 2] & 0xff) << 8) | (m[o + 3] & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream b, int v) {
        b.write(v >>> 24); b.write(v >>> 16); b.write(v >>> 8); b.write(v);
    }
}
