package com.example.voicerecorder;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Offline English speech recognition with Vosk. Audio never leaves the phone. */
public final class LocalTranscriber {

    /** id, download name, label, size, required free bytes for zip + unpacked model */
    public static final String[][] MODELS = {
            {"small", "vosk-model-small-en-us-0.15", "Small", "40 MB", "160"},
            {"large", "vosk-model-en-us-0.22-lgraph", "Better", "128 MB", "420"},
    };

    public static String selected(Context c) { return Storage.prefs(c).getString("voskModel", "small"); }

    private static String[] info(String id) {
        for (String[] m : MODELS) if (m[0].equals(id)) return m;
        return MODELS[0];
    }

    public static String sizeOf(String id) { return info(id)[3]; }
    private static long requiredBytesFor(String id) { return Long.parseLong(info(id)[4]) * 1024L * 1024L; }
    private static final float VOSK_RATE = 16000f;

    private static Model model;
    private static String loadedId;
    private static int activeRecognizers = 0;
    private static Model deferredClose;
    private static final ExecutorService fileWorker = Executors.newSingleThreadExecutor();

    // Persistent model download state (survives dismissing/reopening Settings)
    private static volatile String downloadingId;
    private static volatile String downloadStatus;
    private static volatile boolean cancelDownloadFlag;
    private static volatile Progress downloadListener;

    private LocalTranscriber() { }

    public static File modelDir(Context c, String id) { return new File(c.getFilesDir(), "vosk-" + id); }

    public static File modelDir(Context c) { return modelDir(c, selected(c)); }

    public static boolean isReady(Context c, String id) {
        File d = modelDir(c, id);
        return new File(d, "conf").exists() || new File(d, "am").exists();
    }

    /** True if the selected model, or failing that any model, is downloaded. */
    public static boolean isModelReady(Context c) {
        return isReady(c, selected(c)) || isReady(c, "small") || isReady(c, "large");
    }

    /** Acquires the shared native Vosk model with ref-counting so deleteModel() can never crash an active Recognizer. */
    static synchronized Model acquireModel(Context c) throws Exception {
        String id = isReady(c, selected(c)) ? selected(c) : (isReady(c, "large") ? "large" : "small");
        if (model == null || !id.equals(loadedId)) {
            if (model != null) {
                if (activeRecognizers == 0) model.close();
                else deferredClose = model;
            }
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(modelDir(c, id).getAbsolutePath());
            loadedId = id;
        }
        activeRecognizers++;
        return model;
    }

    static synchronized void releaseModel() {
        if (activeRecognizers > 0) activeRecognizers--;
        if (activeRecognizers == 0 && deferredClose != null) {
            try { deferredClose.close(); } catch (Exception ignored) { }
            deferredClose = null;
        }
    }

    /** Deletes a downloaded model. Returns false if the model is currently in use for transcription. */
    public static synchronized boolean deleteModel(Context c, String id) {
        if (id.equals(loadedId) && model != null) {
            if (activeRecognizers > 0) return false;
            model.close();
            model = null;
            loadedId = null;
        }
        deleteRecursive(modelDir(c, id));
        return true;
    }

    // ---------- Model download ----------
    public interface Progress { void update(String text); void done(boolean ok, String error); }

    public static boolean isDownloading() { return downloadingId != null; }
    public static String downloadingId() { return downloadingId; }
    public static String downloadStatus() { return downloadStatus; }
    public static void setDownloadListener(Progress p) { downloadListener = p; }
    public static void cancelDownload() { cancelDownloadFlag = true; }

    public static synchronized void downloadModel(Context c, String id, Progress p) {
        downloadListener = p;
        if (downloadingId != null) {
            if (p != null && downloadStatus != null) p.update(downloadStatus);
            return;
        }
        Context app = c.getApplicationContext();
        long free = Storage.freeBytes(app);
        if (free < requiredBytesFor(id)) {
            if (p != null) p.done(false, "Need at least " + info(id)[4] + " MB free to download and unpack");
            return;
        }
        downloadingId = id;
        downloadStatus = "Starting download...";
        cancelDownloadFlag = false;
        new Thread(() -> {
            File zip = new File(app.getCacheDir(), "vosk-model-" + id + ".zip");
            File tmp = new File(app.getFilesDir(), "vosk-" + id + ".tmp");
            HttpURLConnection con = null;
            try {
                String url = "https://alphacephei.com/vosk/models/" + info(id)[1] + ".zip";
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(20000);
                con.setReadTimeout(60000);
                int code = con.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Server returned HTTP " + code);
                }
                long total = con.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(con.getInputStream());
                     OutputStream os = new FileOutputStream(zip)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0, lastShown = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelDownloadFlag) throw new IOException("Cancelled");
                        os.write(buf, 0, n);
                        got += n;
                        if (got - lastShown > 512 * 1024) {
                            lastShown = got;
                            String t = total > 0
                                    ? "Downloading " + (got * 100 / total) + "%  (" + (got >> 20) + " of " + (total >> 20) + " MB)"
                                    : "Downloading " + (got >> 20) + " MB";
                            notifyProgress(t);
                        }
                    }
                }
                if (cancelDownloadFlag) throw new IOException("Cancelled");
                notifyProgress("Unpacking...");
                deleteRecursive(tmp);
                tmp.mkdirs();
                String canonTmp = tmp.getCanonicalPath() + File.separator;
                try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
                    ZipEntry e;
                    byte[] buf = new byte[64 * 1024];
                    while ((e = zin.getNextEntry()) != null) {
                        if (cancelDownloadFlag) throw new IOException("Cancelled");
                        String name = e.getName();
                        int slash = name.indexOf('/');
                        if (slash < 0 || slash == name.length() - 1) continue; // skip top folder
                        String rel = name.substring(slash + 1);
                        File out = new File(tmp, rel);
                        // Canonical Zip Slip protection
                        if (!out.getCanonicalPath().startsWith(canonTmp)) continue;
                        if (e.isDirectory()) { out.mkdirs(); continue; }
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (OutputStream os = new FileOutputStream(out)) {
                            int n;
                            while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                        }
                    }
                }
                zip.delete();
                deleteRecursive(modelDir(app, id));
                if (!tmp.renameTo(modelDir(app, id))) throw new IOException("Could not save the model");
                finishDownload(true, null);
            } catch (Exception ex) {
                zip.delete();
                deleteRecursive(tmp);
                finishDownload(false, cancelDownloadFlag ? "Download cancelled" : ex.getMessage());
            } finally {
                if (con != null) con.disconnect();
            }
        }, "vosk-download").start();
    }

    private static void notifyProgress(String text) {
        downloadStatus = text;
        Progress l = downloadListener;
        if (l != null) l.update(text);
    }

    private static void finishDownload(boolean ok, String error) {
        downloadingId = null;
        downloadStatus = null;
        cancelDownloadFlag = false;
        Progress l = downloadListener;
        if (l != null) l.done(ok, error);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    // ---------- Anti-aliased Resampler to 16 kHz ----------
    /**
     * Resamples 16-bit mono PCM to 16 kHz for Vosk.
     * When downsampling (e.g. 44.1/48 kHz -> 16 kHz), integrates input over each fractional
     * output bin (boxcar anti-aliasing filter) to prevent high-frequency aliasing into speech.
     */
    static final class Resampler {
        private final double step;
        // Downsampling integrator state
        private double acc = 0.0;
        private double rem;
        // Upsampling linear interpolation state
        private double pos = 0.0;
        private short prev = 0;

        Resampler(int fromRate) {
            step = fromRate / (double) VOSK_RATE;
            rem = step;
        }

        short[] process(short[] in, int n) {
            if (n <= 0) return new short[0];
            if (Math.abs(step - 1.0) < 1e-6) {
                short[] copy = new short[n];
                System.arraycopy(in, 0, copy, 0, n);
                return copy;
            }
            if (step > 1.0) {
                // Exact fractional area-averaging downsampler (anti-aliased, drift-free)
                short[] out = new short[(int) (n / step) + 2];
                int count = 0;
                for (int i = 0; i < n; i++) {
                    double x = in[i];
                    double avail = 1.0;
                    while (avail >= rem - 1e-12) {
                        double take = Math.min(avail, rem);
                        acc += x * take;
                        int val = (int) Math.round(acc / step);
                        if (val > Short.MAX_VALUE) val = Short.MAX_VALUE;
                        else if (val < Short.MIN_VALUE) val = Short.MIN_VALUE;
                        out[count++] = (short) val;
                        avail -= take;
                        acc = 0.0;
                        rem = step;
                    }
                    if (avail > 1e-12) {
                        acc += x * avail;
                        rem -= avail;
                    }
                }
                short[] r = new short[count];
                System.arraycopy(out, 0, r, 0, count);
                return r;
            } else {
                // Upsampling (< 16 kHz -> 16 kHz) via linear interpolation
                if (n == 1) { prev = in[0]; return new short[0]; }
                short[] out = new short[(int) (n / step) + 2];
                int count = 0;
                while (pos < n - 1) {
                    int i = (int) Math.floor(pos);
                    double f = pos - i;
                    double a = i < 0 ? prev : in[i];
                    double b = in[i + 1];
                    out[count++] = (short) Math.round(a + (b - a) * f);
                    pos += step;
                }
                pos -= n;
                prev = in[n - 1];
                short[] r = new short[count];
                System.arraycopy(out, 0, r, 0, count);
                return r;
            }
        }
    }

    private static String textOf(String json) {
        try { return new JSONObject(json).optString("text", "").trim(); }
        catch (Exception e) { return ""; }
    }

    private static String partialOf(String json) {
        try { return new JSONObject(json).optString("partial", "").trim(); }
        catch (Exception e) { return ""; }
    }

    // ---------- Live session while recording ----------
    /** Receives the same audio the recorder captures and transcribes it on a background thread. */
    public static final class Session {
        private final Context app;
        private final String base;
        private final long partSamples; // at 16 kHz; Long.MAX_VALUE for one full transcript
        private final boolean splitParts;
        private final BlockingQueue<short[]> queue = new ArrayBlockingQueue<>(400);
        private final Resampler resampler;
        private final Thread thread;
        private volatile boolean finishing;

        private final StringBuilder all = new StringBuilder();
        private final StringBuilder part = new StringBuilder();
        private int partIndex = 1;
        private long partCount = 0;

        public Session(Context c, String base, int inputRate, int intervalSeconds) {
            app = c.getApplicationContext();
            this.base = base;
            splitParts = intervalSeconds > 0;
            partSamples = intervalSeconds > 0 ? (long) intervalSeconds * (long) VOSK_RATE : Long.MAX_VALUE;
            resampler = new Resampler(inputRate);
            new File(Storage.recDir(app), base + ".txt").delete();
            thread = new Thread(this::run, "vosk-live");
            thread.start();
        }

        /** Called from the recording thread. Never blocks for long. */
        public void feed(short[] pcm, int n) {
            if (finishing) return;
            short[] copy = new short[n];
            System.arraycopy(pcm, 0, copy, 0, n);
            queue.offer(copy); // if the phone is too slow, audio is skipped rather than stalling recording
        }

        /** Stops after processing what is queued, then writes the final files. */
        public void finish() {
            finishing = true;
            // Ensure sentinel is always enqueued even if queue is full
            if (!queue.offer(new short[0])) {
                queue.poll();
                queue.offer(new short[0]);
            }
            try { thread.join(30000); } catch (InterruptedException ignored) { }
        }

        private void run() {
            Transcriber t = Transcriber.get(app);
            Recognizer rec = null;
            boolean modelAcquired = false;
            try {
                Model m = acquireModel(app);
                modelAcquired = true;
                rec = new Recognizer(m, VOSK_RATE);
                t.status("Transcribing on device...");
                while (true) {
                    short[] in = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (in == null) {
                        if (finishing && queue.isEmpty()) break;
                        continue;
                    }
                    if (in.length == 0) break;
                    short[] s = resampler.process(in, in.length);
                    if (s.length == 0) continue;
                    if (rec.acceptWaveForm(s, s.length)) {
                        add(textOf(rec.getResult()));
                    } else {
                        String partial = partialOf(rec.getPartialResult());
                        t.publish(base, (all + (partial.isEmpty() ? "" : " " + partial)).trim());
                    }
                    partCount += s.length;
                    if (splitParts && partCount >= partSamples) {
                        add(textOf(rec.getFinalResult()));
                        writePart(false);
                    }
                }
                add(textOf(rec.getFinalResult()));
                if (splitParts) writePart(true);
                writeCombined();
                t.status(null);
            } catch (Exception e) {
                t.status("On-device transcription failed: " + e.getMessage());
            } finally {
                if (rec != null) rec.close();
                if (modelAcquired) releaseModel();
            }
        }

        private void add(String text) {
            if (text.isEmpty()) return;
            if (all.length() > 0) all.append(' ');
            all.append(text);
            if (part.length() > 0) part.append(' ');
            part.append(text);
            Transcriber.get(app).publish(base, all.toString());
            GoogleDocsSync.get(app).appendChunk(base, text);
        }

        private void writePart(boolean last) {
            if (last && part.length() == 0 && partIndex > 1) return;
            File f = new File(Storage.recDir(app), base + String.format(Locale.US, "_part%02d.txt", partIndex));
            writeFile(f, part.toString());
            Storage.copyToFolder(app, f);
            part.setLength(0);
            partIndex++;
            partCount = 0;
        }

        private void writeCombined() {
            File f = new File(Storage.recDir(app), base + ".txt");
            writeFile(f, all.toString());
            Storage.copyToFolder(app, f);
        }
    }

    static void writeFile(File f, String text) {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    // ---------- Files: existing recordings and waiting parts ----------
    /** Transcribe a whole recording on the phone. */
    public static void transcribeFile(Context c, File audio) {
        Context app = c.getApplicationContext();
        String base = Transcriber.stem(audio.getName());
        fileWorker.execute(() -> {
            Transcriber t = Transcriber.get(app);
            t.status("Transcribing " + Formatters.displayName(audio.getName()) + " on device...");
            try {
                String text = recognizeFile(app, audio);
                File out = new File(Storage.recDir(app), base + ".txt");
                writeFile(out, text);
                Storage.copyToFolder(app, out);
                t.publish(base, text);
                t.status(null);
            } catch (Exception e) {
                t.status("Could not transcribe: " + e.getMessage());
            }
        });
    }

    /** Transcribe the parts waiting for Groq on the phone instead. */
    public static void transcribePending(Context c) {
        Context app = c.getApplicationContext();
        fileWorker.execute(() -> {
            Transcriber t = Transcriber.get(app);
            List<File> pending = t.pending();
            int done = 0;
            for (File f : pending) {
                Matcher m = Transcriber.NAME.matcher(f.getName());
                if (!m.matches()) continue;
                t.status("Transcribing part " + m.group(2) + " on device...");
                try {
                    String text = recognizeFile(app, f);
                    t.writeText(m.group(1), Integer.parseInt(m.group(2)), m.group(3) != null, text);
                    t.publish(m.group(1), text);
                    f.delete();
                    done++;
                } catch (Exception e) {
                    t.status("Could not transcribe a part: " + e.getMessage());
                    return;
                }
            }
            Storage.prefs(app).edit().putBoolean("txApproval", false).apply();
            t.status(done > 0 ? null : "Nothing to transcribe");
        });
    }

    /** Decodes MP3/M4A to PCM with Android's decoder and runs it through Vosk. */
    static String recognizeFile(Context app, File file) throws Exception {
        MediaExtractor ex = null;
        MediaCodec codec = null;
        Recognizer rec = null;
        boolean modelAcquired = false;
        StringBuilder sb = new StringBuilder();
        try {
            ex = new MediaExtractor();
            ex.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0 || fmt == null) throw new IOException("No audio track found");
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            Resampler rs = null;
            Model m = acquireModel(app);
            modelAcquired = true;
            rec = new Recognizer(m, VOSK_RATE);
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
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    rs = null;
                } else if (oi >= 0) {
                    progressed = true;
                    ByteBuffer out = codec.getOutputBuffer(oi);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        short[] pcm = new short[info.size / 2];
                        out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                        short[] mono;
                        if (channels > 1) {
                            mono = new short[pcm.length / channels];
                            for (int i = 0; i < mono.length; i++) {
                                int sum = 0;
                                for (int ch = 0; ch < channels; ch++) sum += pcm[i * channels + ch];
                                mono[i] = (short) (sum / channels);
                            }
                        } else {
                            mono = pcm;
                        }
                        if (rs == null) rs = new Resampler(rate);
                        short[] s = rs.process(mono, mono.length);
                        if (s.length > 0 && rec.acceptWaveForm(s, s.length)) {
                            String t = textOf(rec.getResult());
                            if (!t.isEmpty()) { if (sb.length() > 0) sb.append(' '); sb.append(t); }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
                idleLoops = progressed ? 0 : idleLoops + 1;
            }
            String t = textOf(rec.getFinalResult());
            if (!t.isEmpty()) { if (sb.length() > 0) sb.append(' '); sb.append(t); }
        } finally {
            if (rec != null) rec.close();
            if (modelAcquired) releaseModel();
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            if (ex != null) { try { ex.release(); } catch (Exception ignored) { } }
        }
        return sb.toString();
    }
}
