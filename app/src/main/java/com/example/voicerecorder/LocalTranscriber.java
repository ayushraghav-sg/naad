package com.example.voicerecorder;

import android.content.Context;
import android.media.AudioFormat;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
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

    /** id, download name, label, size */
    public static final String[][] MODELS = {
            {"small", "vosk-model-small-en-us-0.15", "Small", "40 MB"},
            {"large", "vosk-model-en-us-0.22-lgraph", "Better", "128 MB"},
    };

    public static String selected(Context c) { return Storage.prefs(c).getString("voskModel", "small"); }

    private static String[] info(String id) {
        for (String[] m : MODELS) if (m[0].equals(id)) return m;
        return MODELS[0];
    }

    public static String sizeOf(String id) { return info(id)[3]; }
    private static final float VOSK_RATE = 16000f;

    private static Model model;
    private static final ExecutorService fileWorker = Executors.newSingleThreadExecutor();

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

    private static String loadedId;

    static synchronized Model model(Context c) throws Exception {
        String id = isReady(c, selected(c)) ? selected(c) : (isReady(c, "large") ? "large" : "small");
        if (model == null || !id.equals(loadedId)) {
            if (model != null) model.close();
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(modelDir(c, id).getAbsolutePath());
            loadedId = id;
        }
        return model;
    }

    public static synchronized void deleteModel(Context c, String id) {
        if (id.equals(loadedId) && model != null) { model.close(); model = null; loadedId = null; }
        deleteRecursive(modelDir(c, id));
    }

    // ---------- Model download ----------
    public interface Progress { void update(String text); void done(boolean ok, String error); }

    public static void downloadModel(Context c, String id, Progress p) {
        new Thread(() -> {
            File zip = new File(c.getCacheDir(), "vosk-model.zip");
            File tmp = new File(c.getFilesDir(), "vosk-" + id + ".tmp");
            try {
                String url = "https://alphacephei.com/vosk/models/" + info(id)[1] + ".zip";
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(20000);
                con.setReadTimeout(60000);
                long total = con.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(con.getInputStream());
                     OutputStream os = new FileOutputStream(zip)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0, lastShown = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        got += n;
                        if (got - lastShown > 512 * 1024) {
                            lastShown = got;
                            String t = total > 0
                                    ? "Downloading " + (got * 100 / total) + "%  (" + (got >> 20) + " of " + (total >> 20) + " MB)"
                                    : "Downloading " + (got >> 20) + " MB";
                            p.update(t);
                        }
                    }
                }
                p.update("Unpacking...");
                deleteRecursive(tmp);
                tmp.mkdirs();
                try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
                    ZipEntry e;
                    byte[] buf = new byte[64 * 1024];
                    while ((e = zin.getNextEntry()) != null) {
                        String name = e.getName();
                        int slash = name.indexOf('/');
                        if (slash < 0 || slash == name.length() - 1) continue; // skip top folder
                        String rel = name.substring(slash + 1);
                        if (rel.contains("..")) continue;
                        File out = new File(tmp, rel);
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
                deleteRecursive(modelDir(c, id));
                if (!tmp.renameTo(modelDir(c, id))) throw new Exception("Could not save the model");
                p.done(true, null);
            } catch (Exception ex) {
                zip.delete();
                deleteRecursive(tmp);
                p.done(false, ex.getMessage());
            }
        }).start();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    // ---------- Resampling to 16 kHz ----------
    static final class Resampler {
        private final double step;
        private double pos = 0;
        private short prev = 0;

        Resampler(int fromRate) { step = fromRate / (double) VOSK_RATE; }

        short[] process(short[] in, int n) {
            if (n <= 1) return new short[0];
            short[] out = new short[(int) (n / step) + 2];
            int count = 0;
            while (pos < n - 1) {
                int i = (int) Math.floor(pos);
                double f = pos - i;
                double a = i < 0 ? prev : in[i];
                double b = in[i + 1];
                out[count++] = (short) (a + (b - a) * f);
                pos += step;
            }
            pos -= n;
            prev = in[n - 1];
            short[] r = new short[count];
            System.arraycopy(out, 0, r, 0, count);
            return r;
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

        public Session(Context c, String base, int inputRate, int minutes) {
            app = c.getApplicationContext();
            this.base = base;
            splitParts = minutes > 0;
            partSamples = minutes > 0 ? (long) minutes * 60 * (long) VOSK_RATE : Long.MAX_VALUE;
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
            queue.offer(new short[0]);
            try { thread.join(30000); } catch (InterruptedException ignored) { }
        }

        private void run() {
            Transcriber t = Transcriber.get(app);
            Recognizer rec = null;
            try {
                rec = new Recognizer(model(app), VOSK_RATE);
                t.status("Transcribing on device...");
                while (true) {
                    short[] in = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (in == null) continue;
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
            }
        }

        private void add(String text) {
            if (text.isEmpty()) return;
            if (all.length() > 0) all.append(' ');
            all.append(text);
            if (part.length() > 0) part.append(' ');
            part.append(text);
            Transcriber.get(app).publish(base, all.toString());
        }

        private void writePart(boolean last) {
            if (last && part.length() == 0 && partIndex > 1) return;
            File f = new File(Storage.recDir(app), base + String.format(java.util.Locale.US, "_part%02d.txt", partIndex));
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
            os.write(text.getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    // ---------- Files: existing recordings and waiting parts ----------
    /** Transcribe a whole recording on the phone. */
    public static void transcribeFile(Context c, File audio) {
        Context app = c.getApplicationContext();
        String base = Transcriber.stem(audio.getName());
        fileWorker.execute(() -> {
            Transcriber t = Transcriber.get(app);
            t.status("Transcribing " + base + " on device...");
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
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(file.getAbsolutePath());
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
        }
        if (track < 0) { ex.release(); throw new Exception("No audio found"); }
        ex.selectTrack(track);
        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();

        int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        Resampler rs = null;
        Recognizer rec = new Recognizer(model(app), VOSK_RATE);
        StringBuilder sb = new StringBuilder();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inDone = false, outDone = false;
        try {
            while (!outDone) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(ii);
                        int size = ex.readSampleData(buf, 0);
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
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    rs = null;
                } else if (oi >= 0) {
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
                        } else mono = pcm;
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
            }
            String t = textOf(rec.getFinalResult());
            if (!t.isEmpty()) { if (sb.length() > 0) sb.append(' '); sb.append(t); }
        } finally {
            rec.close();
            codec.stop();
            codec.release();
            ex.release();
        }
        return sb.toString();
    }
}
