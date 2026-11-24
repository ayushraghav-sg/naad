package com.example.voicerecorder;

import android.annotation.SuppressLint;
import android.content.Context;
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
 * Unified PCM audio recorder that encodes to either MP3 (LAME) or M4A (AAC via MediaCodec)
 * on a background thread. Both formats support live waveform, pause/resume, on-device Vosk
 * transcription, and Groq chunked transcription.
 */
public class AudioRecorder {
    private static final int RATE = 44100;
    private static final int CHUNK_KBPS = 32;            // ~14 MB per hour, fine for speech
    private static final int FULL_CAP_MINUTES = 90;      // keeps "full" parts under Groq's 25 MB

    public interface ChunkListener { void onChunk(File chunk); }
    public interface PcmSink { void feed(short[] pcm, int n); }
    public interface ErrorListener { void onError(Exception e); }

    private volatile PcmSink sink;
    private volatile ErrorListener errorListener;

    public void setSink(PcmSink s) { sink = s; }
    public void setErrorListener(ErrorListener l) { errorListener = l; }
    public static int rate() { return RATE; }

    private volatile AudioRecord rec;
    private Thread worker;
    private volatile boolean running, paused;
    private volatile float level;
    private volatile Exception error;

    // transcription chunks
    private File chunkDir;
    private String chunkBase;
    private long chunkLimitSamples;
    private ChunkListener chunkListener;
    private Context appContext;

    /** intervalSeconds: 0 = no chunks, -1 = full recording (capped at 90 min), otherwise chunk length in seconds. */
    public void setChunks(Context c, File dir, String base, int intervalSeconds, ChunkListener l) {
        if (intervalSeconds == 0) return;
        appContext = c.getApplicationContext();
        chunkDir = dir;
        chunkBase = base;
        long sec = intervalSeconds < 0 ? (long) FULL_CAP_MINUTES * 60L : intervalSeconds;
        chunkLimitSamples = sec * RATE;
        chunkListener = l;
    }

    @SuppressLint("MissingPermission") // checked before calling
    public void start(File out, int kbps, boolean useMp3) throws Exception {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("Audio input not supported");
        int bufSize = Math.max(min * 2, RATE / 5 * 2);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new IllegalStateException("Mic is busy");
        }
        r.startRecording();
        if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            r.release();
            throw new IllegalStateException("Mic is busy");
        }
        rec = r;
        running = true;
        paused = false;

        worker = new Thread(() -> runLoop(r, out, kbps, useMp3), useMp3 ? "mp3-encoder" : "aac-encoder");
        worker.start();
    }

    private void runLoop(AudioRecord r, File out, int kbps, boolean useMp3) {
        Lame lame = null;
        OutputStream mp3Os = null;
        AacEncoder aac = null;
        Chunk chunk = null;
        try {
            if (useMp3) {
                mp3Os = new BufferedOutputStream(new FileOutputStream(out));
                lame = new Lame();
                lame.open(1, RATE, kbps, 2);
            } else {
                aac = new AacEncoder(out, RATE, kbps);
            }
            if (chunkDir != null) chunk = new Chunk(1);

            short[] pcm = new short[RATE / 20];
            while (running) {
                int n = r.read(pcm, 0, pcm.length);
                if (n <= 0) {
                    if (!running) break;
                    continue;
                }
                int peak = 0;
                for (int i = 0; i < n; i++) {
                    int a = pcm[i] == Short.MIN_VALUE ? 32767 : Math.abs(pcm[i]);
                    if (a > peak) peak = a;
                }
                if (paused) { level = 0; continue; }
                level = peak / 32767f;

                if (useMp3) {
                    byte[] mp3 = lame.encode(pcm, 0, n);
                    if (mp3 != null && mp3.length > 0) mp3Os.write(mp3);
                } else {
                    aac.encode(pcm, n);
                }

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
            if (lame != null) {
                byte[] tail = lame.close();
                lame = null;
                if (tail != null && tail.length > 0 && mp3Os != null) mp3Os.write(tail);
            }
            if (aac != null) {
                aac.close();
                aac = null;
            }
            if (chunk != null) {
                chunk.finish(true);
                chunk = null;
            }
        } catch (Exception e) {
            error = e;
            running = false;
            ErrorListener el = errorListener;
            if (el != null) el.onError(e);
        } finally {
            if (lame != null) { try { lame.close(); } catch (Exception ignored) { } }
            if (mp3Os != null) { try { mp3Os.close(); } catch (Exception ignored) { } }
            if (aac != null) { try { aac.close(); } catch (Exception ignored) { } }
            if (chunk != null) { chunk.abort(); }
            try { r.stop(); } catch (Exception ignored) { }
            try { r.release(); } catch (Exception ignored) { }
            rec = null;
        }
    }

    /** One transcription part, written to a temp file and renamed when complete. */
    private class Chunk {
        final int index;
        long samples;
        Lame lame;
        final File tmp;
        OutputStream os;

        Chunk(int index) {
            this.index = index;
            tmp = new File(chunkDir, chunkBase + String.format(Locale.US, "__part%02d.tmp", index));
            try {
                os = new BufferedOutputStream(new FileOutputStream(tmp));
                lame = new Lame();
                lame.open(1, RATE, CHUNK_KBPS, 5);
            } catch (Exception e) {
                closeResources();
            }
        }

        void write(short[] pcm, int n) {
            if (os == null || lame == null) return;
            try {
                byte[] b = lame.encode(pcm, 0, n);
                if (b != null && b.length > 0) os.write(b);
                samples += n;
            } catch (Exception ignored) { }
        }

        void finish(boolean last) {
            if (os == null && lame == null) return;
            try {
                if (lame != null) {
                    byte[] t = lame.close();
                    lame = null;
                    if (t != null && t.length > 0 && os != null) os.write(t);
                }
                if (os != null) { os.close(); os = null; }
            } catch (Exception ignored) {
                closeResources();
            }
            // skip near-empty final parts (under 1 second), unless it is the only part
            if (last && samples < RATE && index > 1) {
                tmp.delete();
                File prev = new File(chunkDir, chunkBase + String.format(Locale.US, "__part%02d.mp3", index - 1));
                File prevLast = new File(chunkDir, chunkBase + String.format(Locale.US, "__part%02d_last.mp3", index - 1));
                if (prev.exists() && prev.renameTo(prevLast) && chunkListener != null) chunkListener.onChunk(prevLast);
                else if (appContext != null) Transcriber.get(appContext).finishWithoutLastPart(chunkBase);
                return;
            }
            File done = new File(chunkDir, chunkBase + String.format(Locale.US,
                    last ? "__part%02d_last.mp3" : "__part%02d.mp3", index));
            if (tmp.renameTo(done) && chunkListener != null) chunkListener.onChunk(done);
        }

        void abort() {
            closeResources();
            tmp.delete();
        }

        private void closeResources() {
            if (lame != null) { try { lame.close(); } catch (Exception ignored) { } lame = null; }
            if (os != null) { try { os.close(); } catch (Exception ignored) { } os = null; }
        }
    }

    public void pause() { paused = true; }
    public void resume() { paused = false; }
    public float level() { return level; }

    public void stop() throws Exception {
        running = false;
        AudioRecord r = rec;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) { }
        }
        if (worker != null) {
            worker.join(8000);
        }
        if (error != null) throw error;
    }
}
