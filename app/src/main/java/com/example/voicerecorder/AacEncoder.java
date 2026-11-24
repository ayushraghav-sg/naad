package com.example.voicerecorder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Encodes 16-bit mono PCM audio into an AAC/M4A file using Android's MediaCodec + MediaMuxer.
 * Enables M4A recordings to share the same PCM capture pipeline as MP3 (live waveform, pause,
 * on-device Vosk transcription, and Groq chunking).
 */
public final class AacEncoder implements Closeable {
    private final int sampleRate;
    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private int trackIndex = -1;
    private boolean muxerStarted = false;
    private boolean closed = false;
    private long totalSamples = 0;
    private long lastPtsUs = -1;

    public AacEncoder(File out, int sampleRate, int kbps) throws IOException {
        this.sampleRate = sampleRate;
        MediaCodec c = null;
        MediaMuxer m = null;
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            c.start();
            m = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            this.codec = c;
            this.muxer = m;
        } catch (Exception e) {
            if (c != null) {
                try { c.stop(); } catch (Exception ignored) { }
                try { c.release(); } catch (Exception ignored) { }
            }
            if (m != null) {
                try { m.release(); } catch (Exception ignored) { }
            }
            throw (e instanceof IOException) ? (IOException) e : new IOException("AAC init failed", e);
        }
    }

    /** Encodes n 16-bit mono PCM samples. */
    public void encode(short[] pcm, int n) throws IOException {
        if (closed || n <= 0) return;
        byte[] bytes = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            short s = pcm[i];
            bytes[i * 2] = (byte) (s & 0xFF);
            bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        int offset = 0;
        int retries = 0;
        while (offset < bytes.length && retries < 200) {
            int ii = codec.dequeueInputBuffer(10000);
            if (ii >= 0) {
                retries = 0;
                ByteBuffer buf = codec.getInputBuffer(ii);
                if (buf != null) {
                    buf.clear();
                    int len = Math.min(buf.remaining(), bytes.length - offset);
                    // Keep 2-byte PCM sample alignment
                    len &= ~1;
                    if (len > 0) {
                        buf.put(bytes, offset, len);
                        long ptsUs = (totalSamples * 1_000_000L) / sampleRate;
                        codec.queueInputBuffer(ii, 0, len, ptsUs, 0);
                        offset += len;
                        totalSamples += (len / 2);
                    }
                }
            } else {
                retries++;
            }
            drain(false);
        }
    }

    private void drain(boolean endOfStream) {
        if (endOfStream) {
            for (int r = 0; r < 50; r++) {
                int ii = codec.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    long ptsUs = (totalSamples * 1_000_000L) / sampleRate;
                    codec.queueInputBuffer(ii, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }
            }
        }
        int eosSpins = 0;
        while (true) {
            int oi = codec.dequeueOutputBuffer(info, endOfStream ? 10000 : 0);
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++eosSpins > 100) break;
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    trackIndex = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
            } else if (oi >= 0) {
                eosSpins = 0;
                ByteBuffer outBuf = codec.getOutputBuffer(oi);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size > 0 && muxerStarted && outBuf != null) {
                    if (info.presentationTimeUs < lastPtsUs) {
                        info.presentationTimeUs = lastPtsUs;
                    }
                    lastPtsUs = info.presentationTimeUs;
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    muxer.writeSampleData(trackIndex, outBuf, info);
                }
                codec.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            drain(true);
        } catch (Exception ignored) {
        } finally {
            try { codec.stop(); } catch (Exception ignored) { }
            try { codec.release(); } catch (Exception ignored) { }
            if (muxerStarted) {
                try { muxer.stop(); } catch (Exception ignored) { }
            }
            try { muxer.release(); } catch (Exception ignored) { }
        }
    }
}
