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
import java.util.ArrayDeque;
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
 * Streams the recorder's audio live to an RTMP / RTMPS server as AAC, as one continuous stream.
 *
 * Encoding runs independently of the network. Encoded frames wait in a backlog until the server
 * confirms it received them, so when the internet drops nothing is lost: frames keep collecting
 * (up to {@link #MAX_BACKLOG_MS}) and are sent on reconnect, faster than real time, until the
 * stream has caught up. Timestamps run from the start of the recording and never reset.
 */
public class RtmpStreamer {

    public interface StateListener { void onStreamState(String state, String detail); }

    public static final String CONNECTING = "connecting", LIVE = "live", CATCHING_UP = "catching_up",
            RETRYING = "retrying", FAILED = "failed", STOPPED = "stopped";

    private static final int RATE = 44100;
    private static final int OUT_CHUNK = 4096;
    /** How much audio is held while offline before the oldest is dropped. */
    static final long MAX_BACKLOG_MS = 30 * 60 * 1000L;
    /** Ask the server to confirm receipt every this many bytes. */
    private static final int ACK_WINDOW = 32 * 1024;
    /** A write blocked this long means the connection is dead (e.g. network vanished). */
    private static final long STALL_MS = 8000;
    /** When finishing, how long to keep trying to deliver what is left. */
    private static final long FINAL_FLUSH_MS = 60000;

    private final String tcUrl, host, app, streamName;
    private final int port, kbps;
    private final boolean tls;
    private final StateListener listener;
    private final BlockingQueue<short[]> pcmQueue = new ArrayBlockingQueue<>(400);
    private volatile boolean running = true;    // recording still feeding audio
    private volatile boolean finishing = false; // stop() called: deliver backlog, then end
    private Thread encoderThread, netThread;

    private volatile String state = STOPPED;
    private volatile String lastReported = "";

    // ----- encoded audio waiting for confirmation -----
    private static final class Frame {
        final int ts; final byte[] tag; long endOffset; // byte offset in this connection after sending
        Frame(int ts, byte[] tag) { this.ts = ts; this.tag = tag; }
    }
    private final Object lock = new Object();
    private final ArrayDeque<Frame> pending = new ArrayDeque<>(); // not yet sent on this connection
    private final ArrayDeque<Frame> inflight = new ArrayDeque<>(); // sent, not yet confirmed
    private volatile byte[] aacHeader;   // AAC sequence header tag
    private volatile boolean encoderDone;
    private volatile boolean serverAcks; // server sends receipts; if not, we trust TCP
    private long droppedMs;

    // ----- connection -----
    private volatile Socket socket;
    private OutputStream out;
    private final Object writeLock = new Object();
    private volatile int streamId = -1;
    private volatile boolean published;
    private volatile String error;
    private long bytesRead, lastAck, windowSize = 2500000;
    private long bytesSent;               // since the end of the handshake
    private volatile long ackedBytes;     // as reported by the server
    private volatile long writeStartedAt; // 0 when not inside a write
    private volatile long lastAckAt;      // when the server last confirmed receipt
    /** Without receipts, keep this much sent audio in case the network froze and it never arrived. */
    private static final int NO_RECEIPT_KEEP_MS = 20000;
    /** With receipts: this much unconfirmed data and no receipt for RECEIPT_TIMEOUT_MS means the link is dead. */
    private static final int RECEIPT_GAP_BYTES = 4 * ACK_WINDOW;
    private static final long RECEIPT_TIMEOUT_MS = 10000;

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

    /** Seconds of audio waiting to be sent (not counting audio sent and awaiting the server's receipt). */
    public int backlogSeconds() {
        synchronized (lock) {
            Frame first = pending.peekFirst(), last = pending.peekLast();
            if (first == null || last == null) return 0;
            return Math.max(0, (last.ts - first.ts) / 1000);
        }
    }

    /** True once the server has confirmed receipt at least once on the current connection. */
    public boolean serverConfirms() { return serverAcks; }

    public void start() {
        encoderThread = new Thread(this::encodeLoop, "rtmp-encode");
        netThread = new Thread(this::netLoop, "rtmp-stream");
        encoderThread.start();
        netThread.start();
    }

    /** Called from the recording thread with mono 44.1 kHz PCM. */
    public void feed(short[] pcm, int n) {
        if (!running) return;
        short[] c = new short[n];
        System.arraycopy(pcm, 0, c, 0, n);
        if (!pcmQueue.offer(c)) { pcmQueue.poll(); pcmQueue.offer(c); } // only if the phone itself is overloaded
    }

    /** Ends the stream: encodes what is left, tries to deliver the backlog, then disconnects. Blocks. */
    public void stop() {
        running = false;
        finishing = true;
        pcmQueue.offer(new short[0]);
        try { if (encoderThread != null) encoderThread.join(5000); } catch (InterruptedException ignored) { }
        try { if (netThread != null) netThread.join(FINAL_FLUSH_MS + 5000); } catch (InterruptedException ignored) { }
        closeSocket();
        setState(STOPPED, null);
    }

    private void setState(String s, String detail) {
        state = s;
        String key = s + "|" + detail;
        if (key.equals(lastReported)) return;
        lastReported = key;
        if (listener != null) listener.onStreamState(s, detail);
    }

    // ---------- encoding (independent of the network) ----------
    private void encodeLoop() {
        MediaCodec enc = null;
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long samples = 0;
            boolean end = false;
            while (!end) {
                short[] pcm = pcmQueue.poll(200, TimeUnit.MILLISECONDS);
                if (pcm != null && pcm.length == 0) end = true;
                else if (pcm == null) {
                    if (!running) end = true;
                    else pcm = new short[RATE / 5]; // recording paused: 200 ms of silence keeps the stream continuous
                }
                if (pcm != null && pcm.length > 0) {
                    int off = 0;
                    while (off < pcm.length) {
                        int ii = enc.dequeueInputBuffer(10000);
                        if (ii < 0) { drainEncoder(enc, info); continue; }
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
                drainEncoder(enc, info);
            }
        } catch (Exception e) {
            error = "Audio encoder failed: " + e.getMessage();
        } finally {
            if (enc != null) {
                try { enc.stop(); } catch (Exception ignored) { }
                try { enc.release(); } catch (Exception ignored) { }
            }
            encoderDone = true;
        }
    }

    private void drainEncoder(MediaCodec enc, MediaCodec.BufferInfo info) {
        int oi;
        while ((oi = enc.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                ByteBuffer csd = enc.getOutputFormat().getByteBuffer("csd-0");
                if (csd != null && aacHeader == null) aacHeader = tag(0, csd);
                continue;
            }
            if (oi < 0) continue;
            ByteBuffer ob = enc.getOutputBuffer(oi);
            if (ob != null && info.size > 0) {
                ob.position(info.offset);
                ob.limit(info.offset + info.size);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (aacHeader == null) aacHeader = tag(0, ob);
                } else {
                    addFrame(new Frame((int) (info.presentationTimeUs / 1000), tag(1, ob)));
                }
            }
            enc.releaseOutputBuffer(oi, false);
        }
    }

    private static byte[] tag(int packetType, ByteBuffer data) {
        byte[] t = new byte[2 + data.remaining()];
        t[0] = (byte) 0xAF; // AAC, 44 kHz, 16-bit (stereo flag is always set for AAC)
        t[1] = (byte) packetType; // 0 = sequence header, 1 = raw frame
        data.get(t, 2, t.length - 2);
        return t;
    }

    private void addFrame(Frame f) {
        synchronized (lock) {
            pending.addLast(f);
            // over the limit while offline: drop the oldest audio
            while (true) {
                Frame first = inflight.isEmpty() ? pending.peekFirst() : inflight.peekFirst();
                if (first == null || f.ts - first.ts <= MAX_BACKLOG_MS) break;
                if (!inflight.isEmpty()) inflight.pollFirst(); else pending.pollFirst();
                droppedMs += 23;
            }
            lock.notifyAll();
        }
    }

    // ---------- network: connect, deliver, reconnect ----------
    private void netLoop() {
        int attempt = 0;
        long finishDeadline = 0;
        Thread watchdog = new Thread(this::watchdog, "rtmp-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        while (true) {
            if (finishing && finishDeadline == 0) finishDeadline = SystemClock.elapsedRealtime() + FINAL_FLUSH_MS;
            if (finishing && (allDelivered() || SystemClock.elapsedRealtime() > finishDeadline)) break;
            setState(attempt == 0 ? CONNECTING : RETRYING, attempt == 0 ? null : buffered());
            try {
                session(finishDeadline);
                attempt = 0;
                if (finishing) break; // delivered everything
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (msg.startsWith("REJECTED:")) {
                    setState(FAILED, msg.substring(9));
                    break;
                }
                error = null;
                attempt++;
                setState(RETRYING, buffered());
                long wait = Math.min(10000, 1000L * attempt);
                long until = SystemClock.elapsedRealtime() + wait;
                while (SystemClock.elapsedRealtime() < until) {
                    if (finishing && SystemClock.elapsedRealtime() > finishDeadline && finishDeadline > 0) break;
                    SystemClock.sleep(200);
                    setState(RETRYING, buffered());
                }
            } finally {
                closeSocket();
                requeueUnconfirmed();
            }
        }
        closeSocket();
    }

    private String buffered() {
        int s = backlogSeconds();
        return s <= 1 ? null : s + "s buffered";
    }

    private boolean allDelivered() {
        synchronized (lock) { return encoderDone && pending.isEmpty() && inflight.isEmpty(); }
    }

    /** After a drop, anything the server did not confirm goes back to the front, in order. */
    private void requeueUnconfirmed() {
        synchronized (lock) {
            while (!inflight.isEmpty()) pending.addFirst(inflight.pollLast());
        }
    }

    private void watchdog() {
        while (netThread != null && netThread.isAlive()) {
            long now = SystemClock.elapsedRealtime();
            long w = writeStartedAt;
            boolean stalledWrite = w != 0 && now - w > STALL_MS;
            // data keeps "sending" into phone buffers but the server has stopped confirming it
            boolean silentServer = socket != null && serverAcks
                    && bytesSent - ackedBytes > RECEIPT_GAP_BYTES && now - lastAckAt > RECEIPT_TIMEOUT_MS;
            if (stalledWrite || silentServer) {
                error = "CLOSED: network stalled";
                closeSocket();
            }
            SystemClock.sleep(500);
        }
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        try { if (s != null) s.close(); } catch (Exception ignored) { }
    }

    private void session(long finishDeadline) throws Exception {
        streamId = -1;
        published = false;
        bytesRead = 0;
        lastAck = 0;
        bytesSent = 0;
        ackedBytes = 0;
        serverAcks = false;
        error = null;
        Socket s = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        s.setSendBufferSize(32 * 1024); // keeps little audio "in the air" when the network vanishes
        s.connect(new InetSocketAddress(host, port), 10000);
        s.setTcpNoDelay(true);
        socket = s;
        out = new BufferedOutputStream(s.getOutputStream(), 16 * 1024);
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));

        handshake(in);
        bytesSent = 0;
        final Socket mine = s;
        Thread reader = new Thread(() -> readLoop(in, mine), "rtmp-read");
        reader.setDaemon(true);
        reader.start();

        sendSetChunkSize(OUT_CHUNK);
        sendAckWindow(ACK_WINDOW);
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
        until = SystemClock.elapsedRealtime() + 10000;
        while (aacHeader == null) { // encoder not ready yet
            checkError();
            if (SystemClock.elapsedRealtime() > until) throw new IOException("Audio encoder did not start");
            SystemClock.sleep(20);
        }
        Frame firstPending;
        synchronized (lock) { firstPending = pending.peekFirst(); }
        writeMessage(4, 8, firstPending == null ? 0 : firstPending.ts, streamId, aacHeader);
        deliver(finishDeadline);
    }

    /** Sends waiting frames in order and keeps them until the server confirms receipt. */
    private void deliver(long finishDeadline) throws Exception {
        long tailWait = 0; // once everything is sent at the end, wait briefly for the last receipts
        while (true) {
            checkError();
            releaseConfirmed();
            Frame f;
            synchronized (lock) {
                f = pending.pollFirst();
                if (f == null) {
                    if (finishing && encoderDone) {
                        if (inflight.isEmpty()) break;
                        if (finishDeadline > 0 && SystemClock.elapsedRealtime() > finishDeadline) break;
                        // servers confirm every 32 KB, so the last few seconds may never be confirmed
                        if (tailWait == 0) tailWait = SystemClock.elapsedRealtime() + 3000;
                        else if (SystemClock.elapsedRealtime() > tailWait) { inflight.clear(); break; }
                    }
                    lock.wait(100);
                    continue;
                }
                inflight.addLast(f);
            }
            writeMessage(4, 8, f.ts, streamId, f.tag);
            f.endOffset = bytesSent;
            int behind = backlogSeconds();
            setState(behind > 3 ? CATCHING_UP : LIVE, behind > 3 ? behind + "s behind" : null);
        }
        if (!running) {
            try {
                sendCommand(3, 0, "FCUnpublish", 6, null, streamName);
                sendCommand(3, 0, "deleteStream", 7, null, (double) streamId);
            } catch (Exception ignored) { }
        }
    }

    /** Drops frames the server has confirmed. Servers that never confirm: trust TCP after ~5 s. */
    private void releaseConfirmed() {
        synchronized (lock) {
            long acked = ackedBytes;
            while (!inflight.isEmpty()) {
                Frame f = inflight.peekFirst();
                Frame newest = inflight.peekLast();
                boolean confirmed = serverAcks ? f.endOffset > 0 && f.endOffset <= acked
                        : newest.ts - f.ts > NO_RECEIPT_KEEP_MS;
                if (!confirmed) break;
                inflight.pollFirst();
            }
            if (finishing && encoderDone && pending.isEmpty() && !serverAcks) inflight.clear();
        }
    }

    private void checkError() throws IOException {
        String e = error;
        if (e != null && (e.startsWith("REJECTED:") || e.startsWith("CLOSED") || e.startsWith("Audio"))) throw new IOException(e);
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

    private void sendAckWindow(int size) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeInt(b, size);
        writeMessage(2, 5, 0, 0, b.toByteArray());
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
            if (o == null || socket == null) throw new IOException("Not connected");
            writeStartedAt = SystemClock.elapsedRealtime();
            try {
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
            bytesSent += 12 + (ext ? 4 : 0) + len + ((len - 1) / OUT_CHUNK) * (1 + (ext ? 4 : 0));
            } finally {
                writeStartedAt = 0;
            }
        }
    }

    // ---------- reading the server ----------
    private static final class ChunkState {
        int ts, len, type, msid;
        ByteArrayOutputStream buf;
    }

    private void readLoop(DataInputStream in, Socket mine) {
        Map<Integer, ChunkState> states = new HashMap<>();
        int inChunk = 128;
        try {
            while (socket == mine) {
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
                    if (socket != mine) break;
                    int r = handleMessage(st.type, msg);
                    if (r > 0) inChunk = r;
                }
            }
        } catch (Exception e) {
            if (socket == mine) { // ignore readers of earlier, already replaced connections
                if (error == null) error = "CLOSED: connection lost";
                closeSocket();
            }
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
            case 3: // acknowledgement: total bytes the server has received
                ackedBytes = (readInt(m, 0) & 0xffffffffL) - 3073; // minus the handshake
                serverAcks = true;
                lastAckAt = SystemClock.elapsedRealtime();
                synchronized (lock) { lock.notifyAll(); }
                return 0;
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
