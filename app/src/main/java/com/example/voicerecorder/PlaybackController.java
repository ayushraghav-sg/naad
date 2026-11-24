package com.example.voicerecorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.io.File;

/**
 * Manages recording playback with AudioFocus, headphone-unplug pause, async preparation,
 * and variable playback speed (1x / 1.25x / 1.5x / 2x).
 */
public final class PlaybackController {

    public interface Listener {
        void onPlaybackChanged();
        void onPlaybackError(String message);
    }

    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f};
    private static final String[] SPEED_LABELS = {"1x", "1.25x", "1.5x", "2x"};

    private final Context app;
    private final AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private MediaPlayer player;
    private File playingFile;
    private boolean prepared;
    private int speedIndex = 0;
    private AudioFocusRequest focusRequest;
    private boolean noisyRegistered;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (player == null || !prepared) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            try { player.setVolume(0.25f, 0.25f); } catch (Exception ignored) { }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            try { player.setVolume(1f, 1f); } catch (Exception ignored) { }
        }
    };

    public PlaybackController(Context c, Listener l) {
        app = c.getApplicationContext();
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        listener = l;
    }

    public boolean isCurrent(File f) { return f != null && f.equals(playingFile) && player != null; }
    public boolean isPlaying() { return player != null && prepared && safeIsPlaying(); }
    public boolean hasActiveFile() { return playingFile != null && player != null; }
    public File currentFile() { return playingFile; }
    public String speedLabel() { return SPEED_LABELS[speedIndex]; }

    public int duration() {
        if (player == null || !prepared) return 1;
        try { return Math.max(1, player.getDuration()); } catch (Exception e) { return 1; }
    }

    public int position() {
        if (player == null || !prepared) return 0;
        try { return Math.max(0, player.getCurrentPosition()); } catch (Exception e) { return 0; }
    }

    public void toggle(File f) {
        if (isCurrent(f)) {
            if (!prepared) return;
            if (safeIsPlaying()) {
                pause();
            } else {
                if (requestFocus()) {
                    try {
                        player.start();
                        applySpeedIfPlaying();
                        registerNoisy();
                    } catch (Exception ignored) { }
                }
                listener.onPlaybackChanged();
            }
            return;
        }
        stop();
        if (!requestFocus()) {
            listener.onPlaybackError("Audio is busy");
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                prepared = true;
                try {
                    p.start();
                    applySpeedIfPlaying();
                    registerNoisy();
                } catch (Exception e) {
                    stop();
                    listener.onPlaybackError("Could not play this file");
                    return;
                }
                listener.onPlaybackChanged();
            });
            mp.setOnCompletionListener(p -> {
                stop();
                listener.onPlaybackChanged();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                stop();
                listener.onPlaybackError("Could not play this file");
                return true;
            });
            player = mp;
            playingFile = f;
            prepared = false;
            mp.prepareAsync();
            listener.onPlaybackChanged();
        } catch (Exception e) {
            stop();
            listener.onPlaybackError("Could not play this file");
        }
    }

    public void pause() {
        if (player != null && prepared && safeIsPlaying()) {
            try { player.pause(); } catch (Exception ignored) { }
            unregisterNoisy();
            listener.onPlaybackChanged();
        }
    }

    public void seekTo(int ms) {
        if (player == null || !prepared) return;
        int clamped = Math.max(0, Math.min(duration(), ms));
        try { player.seekTo(clamped); } catch (Exception ignored) { }
    }

    public void skip(int deltaMs) {
        if (player == null || !prepared) return;
        seekTo(position() + deltaMs);
    }

    public void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        applySpeedIfPlaying();
        listener.onPlaybackChanged();
    }

    private void applySpeedIfPlaying() {
        if (player == null || !prepared || !safeIsPlaying()) return;
        try {
            PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(SPEEDS[speedIndex]);
            player.setPlaybackParams(params);
        } catch (Exception ignored) { }
    }

    public void stop() {
        unregisterNoisy();
        abandonFocus();
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        playingFile = null;
        prepared = false;
    }

    public void release() {
        stop();
    }

    private boolean safeIsPlaying() {
        try { return player != null && player.isPlaying(); } catch (Exception e) { return false; }
    }

    private boolean requestFocus() {
        if (audioManager == null) return true;
        abandonFocus();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setOnAudioFocusChangeListener(focusListener, main)
                .build();
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            try { audioManager.abandonAudioFocusRequest(focusRequest); } catch (Exception ignored) { }
            focusRequest = null;
        }
    }

    private void registerNoisy() {
        if (noisyRegistered) return;
        try {
            ContextCompat.registerReceiver(app, noisyReceiver,
                    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            noisyRegistered = true;
        } catch (Exception ignored) { }
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try { app.unregisterReceiver(noisyReceiver); } catch (Exception ignored) { }
        noisyRegistered = false;
    }
}
