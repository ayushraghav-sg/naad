package com.example.voicerecorder;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.github.axet.lamejni.Lame;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Records mic audio as PCM and encodes it to MP3 with LAME on a background thread.
 * Optionally also writes small low-bitrate MP3 "chunks" for transcription.
 */
public class Mp3Recorder {
    private static final int RATE = 44100;
    private static final int CHUNK_KBPS = 32;            // ~14 MB per hour, fine for speech
    private static final int FULL_CAP_MINUTES = 90;      // keeps "full" parts under Groq's 25 MB

    public interface ChunkListener { void onChunk(File chunk); }
    public interface PcmSink { void feed(short[] pcm, int n); }
    private volatile PcmSink sink;
    public void setSink(PcmSink s) { sink = s; }
    public static int rate() { return RATE; }

    private AudioRecord rec;
    private Thread worker;
    private volatile boolean running, paused;
    private volatile float level;
    private volatile Exception error;

    // transcription chunks
    private File chunkDir;
    private String chunkBase;
    private long chunkLimitSamples;
    private ChunkListener chunkListener;
    private android.content.Context appContext;

    /** minutes: 0 = no chunks, -1 = one part for the whole recording, otherwise part length. */
    public void setChunks(android.content.Context c, File dir, String base, int minutes, ChunkListener l) {
        if (minutes == 0) return;
        appContext = c.getApplicationContext();
        chunkDir = dir;
        chunkBase = base;
        int m = minutes < 0 ? FULL_CAP_MINUTES : minutes;
        chunkLimitSamples = (long) m * 60 * RATE;
        chunkListener = l;
    }

    @SuppressLint("MissingPermission") // checked by MainActivity before calling
    public void start(File out, int kbps) throws Exception {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(min, RATE / 5 * 2);
        rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            rec = null;
            throw new IllegalStateException("Mic is busy");
        }
        rec.startRecording();
        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release();
            rec = null;
            throw new IllegalStateException("Mic is busy");
        }
        running = true;
        paused = false;

        worker = new Thread(() -> {
            Lame lame = new Lame();
            lame.open(1, RATE, kbps, 2);
            Chunk chunk = chunkDir != null ? new Chunk(1) : null;
            short[] pcm = new short[RATE / 20];
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                while (running) {
                    int n = rec.read(pcm, 0, pcm.length);
                    if (n <= 0) continue;
                    int peak = 0;
                    for (int i = 0; i < n; i++) {
                        int a = Math.abs(pcm[i]);
                        if (a > peak) peak = a;
                    }
                    if (paused) { level = 0; continue; }
                    level = peak / 32767f;
                    byte[] mp3 = lame.encode(pcm, 0, n);
                    if (mp3 != null && mp3.length > 0) os.write(mp3);
                    PcmSink sk = sink;
                    if (sk != null) sk.feed(pcm, n);

                    if (chunk != null) {
                        chunk.write(pcm, n);
                        if (chunk.samples >= chunkLimitSamples) {
                            chunk.finish(false);
                            chunk = new Chunk(chunk.index + 1);
                        }
                    }
                }
                byte[] tail = lame.close();
                if (tail != null && tail.length > 0) os.write(tail);
                if (chunk != null) chunk.finish(true);
            } catch (Exception e) {
                error = e;
            }
        }, "mp3-encoder");
        worker.start();
    }

    /** One transcription part, written to a temp file and renamed when complete. */
    private class Chunk {
        final int index;
        long samples;
        final Lame lame = new Lame();
        final File tmp;
        OutputStream os;

        Chunk(int index) {
            this.index = index;
            tmp = new File(chunkDir, chunkBase + String.format(Locale.US, "__part%02d.tmp", index));
            lame.open(1, RATE, CHUNK_KBPS, 5);
            try { os = new BufferedOutputStream(new FileOutputStream(tmp)); }
            catch (Exception e) { os = null; }
        }

        void write(short[] pcm, int n) {
            if (os == null) return;
            try {
                byte[] b = lame.encode(pcm, 0, n);
                if (b != null && b.length > 0) os.write(b);
                samples += n;
            } catch (Exception ignored) { }
        }

        void finish(boolean last) {
            if (os == null) return;
            try {
                byte[] t = lame.close();
                if (t != null && t.length > 0) os.write(t);
                os.close();
            } catch (Exception ignored) { }
            // skip near-empty final parts (under 1 second), unless it is the only part
            if (last && samples < RATE && index > 1) {
                tmp.delete();
                // mark the previous part as the last one
                File prev = new File(chunkDir, chunkBase + String.format(Locale.US, "__part%02d.mp3", index - 1));
                File prevLast = new File(chunkDir, chunkBase + String.format(Locale.US, "__part%02d_last.mp3", index - 1));
                if (prev.exists() && prev.renameTo(prevLast) && chunkListener != null) chunkListener.onChunk(prevLast);
                else Transcriber.get(appContext).finishWithoutLastPart(chunkBase);
                return;
            }
            File done = new File(chunkDir, chunkBase + String.format(Locale.US,
                    last ? "__part%02d_last.mp3" : "__part%02d.mp3", index));
            if (tmp.renameTo(done) && chunkListener != null) chunkListener.onChunk(done);
        }
    }

    public void pause() { paused = true; }
    public void resume() { paused = false; }
    public float level() { return level; }

    public void stop() throws Exception {
        running = false;
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
        }
        if (worker != null) worker.join(5000);
        if (rec != null) { rec.release(); rec = null; }
        if (error != null) throw error;
    }
}
