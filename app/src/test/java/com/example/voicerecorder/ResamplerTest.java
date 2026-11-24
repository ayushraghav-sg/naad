package com.example.voicerecorder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ResamplerTest {

    @Test
    public void downsample44100To16000_preservesDcAndExactLength() {
        LocalTranscriber.Resampler rs = new LocalTranscriber.Resampler(44100);
        short[] in = new short[44100];
        for (int i = 0; i < in.length; i++) in[i] = 12345;
        short[] out = rs.process(in, in.length);
        assertEquals(16000, out.length);
        for (short s : out) {
            assertEquals(12345, s);
        }
    }

    @Test
    public void downsample44100To16000_isChunkSizeInvariant() {
        short[] signal = new short[4410];
        for (int i = 0; i < signal.length; i++) {
            signal[i] = (short) (10000 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }

        LocalTranscriber.Resampler whole = new LocalTranscriber.Resampler(44100);
        short[] expected = whole.process(signal, signal.length);

        LocalTranscriber.Resampler chunked = new LocalTranscriber.Resampler(44100);
        short[] combined = new short[expected.length];
        int pos = 0;
        for (int offset = 0; offset < signal.length; offset += 257) {
            int len = Math.min(257, signal.length - offset);
            short[] slice = new short[len];
            System.arraycopy(signal, offset, slice, 0, len);
            short[] part = chunked.process(slice, len);
            System.arraycopy(part, 0, combined, pos, part.length);
            pos += part.length;
        }

        assertEquals(expected.length, pos);
        for (int i = 0; i < expected.length; i++) {
            assertTrue("Mismatch at " + i, Math.abs(expected[i] - combined[i]) <= 1);
        }
    }

    @Test
    public void downsample44100To16000_attenuatesAliasedHighFrequencies() {
        // 16 kHz tone at 44.1 kHz sample rate is well above 8 kHz Nyquist of 16 kHz output
        LocalTranscriber.Resampler rs = new LocalTranscriber.Resampler(44100);
        short[] highTone = new short[4410];
        for (int i = 0; i < highTone.length; i++) {
            highTone[i] = (short) (20000 * Math.sin(2.0 * Math.PI * 16000.0 * i / 44100.0));
        }
        short[] out = rs.process(highTone, highTone.length);
        int maxAmp = 0;
        for (short s : out) maxAmp = Math.max(maxAmp, Math.abs(s));
        // Boxcar anti-aliasing filter heavily attenuates a 16 kHz tone (zero of sinc at 16 kHz)
        assertTrue("Expected heavy attenuation, got peak " + maxAmp, maxAmp < 2000);
    }

    @Test
    public void passthrough16000_copiesUnchanged() {
        LocalTranscriber.Resampler rs = new LocalTranscriber.Resampler(16000);
        short[] in = {10, -20, 300, -4000};
        short[] out = rs.process(in, in.length);
        assertEquals(in.length, out.length);
        for (int i = 0; i < in.length; i++) assertEquals(in[i], out[i]);
    }
}
