package com.example.voicerecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import com.github.axet.lamejni.Lame;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends audio chunks to Groq (Whisper) one at a time, in order, and writes text files.
 * Chunks wait in a private "pending" folder. If there is no internet, nothing is sent
 * until the user approves it in the app.
 */
public final class Transcriber {

    public interface Listener {
        void onTranscript(String base, String text);
        void onStatus(String status);
        void onNeedsApproval(int waiting);
    }

    public interface KeyCheckCallback {
        void onResult(boolean ok, String message);
    }

    private static final String URL_GROQ = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String URL_MODELS = "https://api.groq.com/openai/v1/models";
    private static final long GROQ_DIRECT_LIMIT = 24L * 1024 * 1024;
    private static final int SPLIT_KBPS = 32;
    private static final int SPLIT_MINUTES = 60; // ~14 MB at 32 kbps, well under Groq's 25 MB limit

    static final Pattern NAME = Pattern.compile("(.+)__part(\\d+)(_last)?\\.(mp3|m4a)");

    /** Sorts chunk filenames by base name, then numeric part index (so part100 comes after part99). */
    static final Comparator<String> CHUNK_NAME_ORDER = (a, b) -> {
        Matcher ma = NAME.matcher(a);
        Matcher mb = NAME.matcher(b);
        if (ma.matches() && mb.matches()) {
            int cmp = ma.group(1).compareTo(mb.group(1));
            if (cmp != 0) return cmp;
            return Integer.compare(Integer.parseInt(ma.group(2)), Integer.parseInt(mb.group(2)));
        }
        return a.compareTo(b);
    };

    private static Transcriber instance;

    public static synchronized Transcriber get(Context c) {
        if (instance == null) instance = new Transcriber(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile Listener listener;

    private Transcriber(Context c) { app = c; }

    public void setListener(Listener l) { listener = l; }

    private SharedPreferences prefs() { return Storage.prefs(app); }

    public static File pendingDir(Context c) {
        File d = new File(c.getFilesDir(), "pending");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public List<File> pending() {
        File[] arr = pendingDir(app).listFiles((d, n) -> NAME.matcher(n).matches());
        List<File> out = new ArrayList<>();
        if (arr != null) {
            Arrays.sort(arr, (a, b) -> CHUNK_NAME_ORDER.compare(a.getName(), b.getName()));
            out.addAll(Arrays.asList(arr));
        }
        return out;
    }

    public boolean needsApproval() { return prefs().getBoolean("txApproval", false); }

    public boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /** Called when a new chunk file is ready in the pending folder. */
    public void chunkReady() {
        if (needsApproval()) {
            int n = pending().size();
            post(() -> { Listener l = listener; if (l != null) l.onNeedsApproval(n); });
            return;
        }
        kick();
    }

    /** Start sending if allowed. Safe to call any time. */
    public void kick() {
        if (running || needsApproval() || pending().isEmpty()) return;
        running = true;
        worker.execute(this::drain);
    }

    /** Last part was too short to send: copy the finished transcript once earlier parts are done. */
    public void finishWithoutLastPart(String base) {
        worker.execute(() -> {
            File combined = new File(Storage.recDir(app), base + ".txt");
            if (combined.exists()) Storage.copyToFolder(app, combined);
        });
    }

    public void approveAndSend() {
        prefs().edit().putBoolean("txApproval", false).apply();
        kick();
    }

    public void discardAll() {
        for (File f : pending()) f.delete();
        prefs().edit().putBoolean("txApproval", false).apply();
    }

    /** Updates pending offline chunks when a recording is renamed. */
    public void renamePending(String oldBase, String newBase) {
        for (File f : pending()) {
            Matcher m = NAME.matcher(f.getName());
            if (m.matches() && oldBase.equals(m.group(1))) {
                String suffix = f.getName().substring(oldBase.length());
                f.renameTo(new File(f.getParentFile(), newBase + suffix));
            }
        }
    }

    /** Removes any pending offline chunks belonging to a deleted recording. */
    public void deletePending(String base) {
        for (File f : pending()) {
            Matcher m = NAME.matcher(f.getName());
            if (m.matches() && base.equals(m.group(1))) {
                f.delete();
            }
        }
        if (pending().isEmpty()) {
            prefs().edit().putBoolean("txApproval", false).apply();
        }
    }

    /**
     * Queues an existing recording for Groq transcription.
     * Files under 24 MB are copied directly; larger files are transcoded & split into 32 kbps MP3
     * chunks on the worker thread so long recordings can still be transcribed on the free tier.
     */
    public void queueWholeFile(File rec) {
        String base = stem(rec.getName());
        worker.execute(() -> {
            deletePending(base);
            if (rec.length() <= GROQ_DIRECT_LIMIT) {
                String ext = rec.getName().endsWith(".m4a") ? "m4a" : "mp3";
                File dst = new File(pendingDir(app), base + "__part01_last." + ext);
                try (InputStream in = new FileInputStream(rec);
                     FileOutputStream os = new FileOutputStream(dst)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } catch (IOException e) {
                    dst.delete();
                    status("Could not prepare file for Groq.");
                    return;
                }
            } else {
                status("Splitting large recording for Groq...");
                if (!splitLargeFile(rec, base)) {
                    status("Could not prepare large file for Groq.");
                    return;
                }
            }
            if (!running) {
                running = true;
                drain();
            }
        });
    }

    /** Decodes a >24 MB recording and encodes it into <=60-minute 32 kbps mono MP3 chunks. */
    private boolean splitLargeFile(File rec, String base) {
        MediaExtractor ex = null;
        MediaCodec codec = null;
        Lame lame = null;
        OutputStream os = null;
        File tmp = null;
        List<File> created = new ArrayList<>();
        try {
            ex = new MediaExtractor();
            ex.setDataSource(rec.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0 || fmt == null) return false;
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            long limitSamples = (long) SPLIT_MINUTES * 60 * rate;

            int partIndex = 1;
            long samplesInPart = 0;
            tmp = new File(pendingDir(app), base + String.format(Locale.US, "__part%02d.tmp", partIndex));
            lame = new Lame();
            lame.open(1, rate, SPLIT_KBPS, 5);
            os = new BufferedOutputStream(new FileOutputStream(tmp));

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int idleLoops = 0;
            while (!outDone && idleLoops < 5000) {
                boolean progressed = false;
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        progressed = true;
                        ByteBuffer buf = codec.getInputBuffer(ii);
                        int size = buf != null ? ex.readSampleData(buf, 0) : -1;
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true;
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (oi >= 0) {
                    progressed = true;
                    ByteBuffer out = codec.getOutputBuffer(oi);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        short[] pcm = new short[info.size / 2];
                        out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                        short[] mono = downmixMono(pcm, channels);
                        byte[] mp3 = lame.encode(mono, 0, mono.length);
                        if (mp3 != null && mp3.length > 0) os.write(mp3);
                        samplesInPart += mono.length;
                        if (samplesInPart >= limitSamples) {
                            byte[] tail = lame.close();
                            if (tail != null && tail.length > 0) os.write(tail);
                            os.close();
                            os = null;
                            lame = null;
                            File done = new File(pendingDir(app),
                                    base + String.format(Locale.US, "__part%02d.mp3", partIndex));
                            if (tmp.renameTo(done)) created.add(done);
                            partIndex++;
                            samplesInPart = 0;
                            tmp = new File(pendingDir(app),
                                    base + String.format(Locale.US, "__part%02d.tmp", partIndex));
                            lame = new Lame();
                            lame.open(1, rate, SPLIT_KBPS, 5);
                            os = new BufferedOutputStream(new FileOutputStream(tmp));
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
                idleLoops = progressed ? 0 : idleLoops + 1;
            }
            if (lame != null) {
                byte[] tail = lame.close();
                if (tail != null && tail.length > 0 && os != null) os.write(tail);
                lame = null;
            }
            if (os != null) { os.close(); os = null; }
            if (tmp != null && tmp.exists()) {
                if (samplesInPart > 0 || created.isEmpty()) {
                    File done = new File(pendingDir(app),
                            base + String.format(Locale.US, "__part%02d_last.mp3", partIndex));
                    if (tmp.renameTo(done)) created.add(done);
                } else {
                    tmp.delete();
                    File last = created.get(created.size() - 1);
                    File renamed = new File(last.getParentFile(),
                            last.getName().replace(".mp3", "_last.mp3"));
                    last.renameTo(renamed);
                }
            }
            return !created.isEmpty();
        } catch (Exception e) {
            for (File f : created) f.delete();
            if (tmp != null) tmp.delete();
            return false;
        } finally {
            if (lame != null) { try { lame.close(); } catch (Exception ignored) { } }
            if (os != null) { try { os.close(); } catch (Exception ignored) { } }
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            if (ex != null) { try { ex.release(); } catch (Exception ignored) { } }
        }
    }

    private static short[] downmixMono(short[] pcm, int channels) {
        if (channels <= 1) return pcm;
        short[] mono = new short[pcm.length / channels];
        for (int i = 0; i < mono.length; i++) {
            int sum = 0;
            for (int ch = 0; ch < channels; ch++) sum += pcm[i * channels + ch];
            mono[i] = (short) (sum / channels);
        }
        return mono;
    }

    /** Verifies a Groq API key by calling GET /models on a background thread. */
    public void testApiKey(String rawKey, KeyCheckCallback cb) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isEmpty()) {
            post(() -> cb.onResult(false, "Enter an API key first"));
            return;
        }
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(URL_MODELS).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestMethod("GET");
                c.setRequestProperty("Authorization", "Bearer " + key);
                int code = c.getResponseCode();
                if (code == 200) post(() -> cb.onResult(true, "API key is valid"));
                else if (code == 401) post(() -> cb.onResult(false, "Invalid API key"));
                else post(() -> cb.onResult(false, "Groq returned HTTP " + code));
            } catch (Exception e) {
                post(() -> cb.onResult(false, "Could not reach Groq: " + e.getMessage()));
            } finally {
                if (c != null) c.disconnect();
            }
        }, "groq-key-test").start();
    }

    private void drain() {
        try {
            while (true) {
                List<File> p = pending();
                if (p.isEmpty()) { status(null); break; }
                if (!isOnline()) {
                    prefs().edit().putBoolean("txApproval", true).apply();
                    status("No internet. " + p.size() + (p.size() == 1 ? " part is" : " parts are") + " waiting to be sent.");
                    int n = p.size();
                    post(() -> { Listener l = listener; if (l != null) l.onNeedsApproval(n); });
                    break;
                }
                String key = Storage.groqKey(app);
                if (key.isEmpty()) { status("Add your Groq API key in Settings to transcribe."); break; }

                File f = p.get(0);
                Matcher m = NAME.matcher(f.getName());
                if (!m.matches()) { f.delete(); continue; }
                String base = m.group(1);
                int part = Integer.parseInt(m.group(2));
                boolean last = m.group(3) != null;

                status("Transcribing part " + part + "...");
                String text;
                try {
                    text = send(f, key);
                } catch (ApiException e) {
                    status(e.getMessage());
                    break; // keep the chunk; user can retry later
                } catch (IOException e) {
                    prefs().edit().putBoolean("txApproval", true).apply();
                    status("Connection failed. Parts are waiting to be sent.");
                    int n = pending().size();
                    post(() -> { Listener l = listener; if (l != null) l.onNeedsApproval(n); });
                    break;
                }
                String shown = text == null ? "" : text.trim();
                writeText(base, part, last, shown);
                f.delete();
                post(() -> { Listener l = listener; if (l != null) l.onTranscript(base, shown); });
            }
        } finally {
            running = false;
        }
    }

    public void writeText(String base, int part, boolean last, String text) {
        File dir = Storage.recDir(app);
        File combined = new File(dir, base + ".txt");
        if (part == 1) {
            // Fresh transcript (or re-transcribe): clear stale part files and overwrite combined file
            File[] oldParts = dir.listFiles((d, n) -> n.startsWith(base + "_part") && n.endsWith(".txt"));
            if (oldParts != null) for (File op : oldParts) op.delete();
        }
        boolean append = part > 1 && combined.exists() && combined.length() > 0;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(combined, append), StandardCharsets.UTF_8)) {
            if (append && !text.isEmpty()) w.write("\n\n");
            w.write(text);
        } catch (IOException ignored) { }

        boolean single = last && part == 1;
        if (!single) {
            File pf = new File(dir, base + String.format(Locale.US, "_part%02d.txt", part));
            try (Writer w = new OutputStreamWriter(new FileOutputStream(pf), StandardCharsets.UTF_8)) {
                w.write(text);
            } catch (IOException ignored) { }
            Storage.copyToFolder(app, pf);
        }
        if (last) Storage.copyToFolder(app, combined);
    }

    static class ApiException extends Exception {
        ApiException(String m) { super(m); }
    }

    private String send(File f, String key) throws IOException, ApiException {
        String boundary = "----naad" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(URL_GROQ).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setChunkedStreamingMode(64 * 1024);

            String mime = f.getName().endsWith(".m4a") ? "audio/mp4" : "audio/mpeg";
            String safeFileName = f.getName().replaceAll("[\"\\r\\n]", "_");
            String model = Storage.groqModel(app);
            String lang = Storage.groqLanguage(app);

            try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
                field(out, boundary, "model", model);
                if (!lang.isEmpty()) field(out, boundary, "language", lang);
                field(out, boundary, "response_format", "text");
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + safeFileName + "\"\r\n");
                out.writeBytes("Content-Type: " + mime + "\r\n\r\n");
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                out.writeBytes("\r\n--" + boundary + "--\r\n");
            }

            int code = c.getResponseCode();
            if (code == 200) return read(c.getInputStream());
            String body = c.getErrorStream() != null ? read(c.getErrorStream()) : "";
            if (code == 401) throw new ApiException("Groq API key is not valid. Check it in Settings.");
            if (code == 429) throw new ApiException("Groq limit reached. Try again later from the recording's menu.");
            if (code == 413) throw new ApiException("Part too large for Groq.");
            throw new ApiException("Groq error " + code + (body.length() > 120 ? "" : ": " + body));
        } finally {
            c.disconnect();
        }
    }

    private static void field(DataOutputStream out, String b, String name, String value) throws IOException {
        out.writeBytes("--" + b + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private static String read(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Used by the on-device engine to update the screen. */
    public void publish(String base, String text) {
        post(() -> { Listener l = listener; if (l != null) l.onTranscript(base, text); });
    }

    public void status(String s) {
        post(() -> { Listener l = listener; if (l != null) l.onStatus(s); });
    }

    private void post(Runnable r) { main.post(r); }

    public static String stem(String n) {
        return Formatters.stripExt(n);
    }
}
