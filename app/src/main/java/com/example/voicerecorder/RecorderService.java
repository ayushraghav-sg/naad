package com.example.voicerecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import java.io.File;

/** Foreground service that keeps recording when the app is in the background or the screen is off. */
public class RecorderService extends Service {

    public static final String ACTION_START = "naad.START";
    public static final String ACTION_PAUSE = "naad.PAUSE";
    public static final String ACTION_RESUME = "naad.RESUME";
    public static final String ACTION_STOP = "naad.STOP";
    public static final String EXTRA_MP3 = "mp3";
    public static final String EXTRA_KBPS = "kbps";
    public static final String EXTRA_PRIVATE = "private";
    public static final String EXTRA_STREAM = "stream";

    public static final long LOW_SPACE = 50L * 1024 * 1024; // auto-stop below 50 MB
    private static final long SPACE_CHECK_MS = 5000L;

    private static final String CHANNEL = "recording";
    private static final String ALERT_CHANNEL = "alerts";
    private static final int NOTIF_ID = 7;
    private static final int ALERT_NOTIF_ID = 8;

    public interface Listener {
        void onStateChanged();
        void onError(String message);
        void onSaved(File file, boolean copiedToFolder);
    }

    public class LocalBinder extends Binder {
        public RecorderService get() { return RecorderService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    private AudioRecorder recorder;
    private LocalTranscriber.Session localSession;
    private boolean privateRec, streamRec;
    private RtmpStreamer streamer;
    private volatile String streamState, streamDetail;
    private File file;
    private boolean recording, paused, pausedByFocus;
    private int sessionId = 0;
    private long startedAt, pausedTotal, pauseStart;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private File lastSaved;
    private String pendingError;

    private final Runnable spaceCheck = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            if (Storage.freeBytes(RecorderService.this) < LOW_SPACE) {
                notifyError("Storage almost full. Recording stopped and saved.");
                stop();
                return;
            }
            main.postDelayed(this, SPACE_CHECK_MS);
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (!recording) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (!paused) {
                pausedByFocus = true;
                pause();
            }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (paused && pausedByFocus) {
                pausedByFocus = false;
                resume();
            }
        }
    };

    @Override public IBinder onBind(Intent i) { return binder; }

    public void setListener(Listener l) { listener = l; }
    public boolean isRecording() { return recording; }
    public boolean isPaused() { return paused; }
    public File currentFile() { return file; }

    public long elapsed() {
        if (!recording) return 0;
        long now = paused ? pauseStart : SystemClock.elapsedRealtime();
        return Math.max(0, now - startedAt - pausedTotal);
    }

    public float level() {
        if (!recording || paused || recorder == null) return 0;
        return recorder.level();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(a)) {
            privateRec = intent.getBooleanExtra(EXTRA_PRIVATE, false);
            streamRec = intent.getBooleanExtra(EXTRA_STREAM, false) && !privateRec;
            start(intent.getBooleanExtra(EXTRA_MP3, true), intent.getIntExtra(EXTRA_KBPS, 128));
        } else if (ACTION_PAUSE.equals(a)) {
            pausedByFocus = false;
            pause();
        } else if (ACTION_RESUME.equals(a)) {
            pausedByFocus = false;
            resume();
        } else if (ACTION_STOP.equals(a)) {
            stop();
        }
        return START_NOT_STICKY;
    }

    // ---------- Recording ----------
    private void start(boolean useMp3, int kbps) {
        if (recording) return;
        sessionId++;
        final int thisSession = sessionId;
        file = new File(Storage.recDir(this), Storage.newName(this, useMp3));

        // Must go foreground right away (Android requirement for microphone services)
        if (!goForeground()) {
            file = null;
            finishService();
            notifyError("Microphone permission is needed to record.");
            return;
        }

        requestFocus();
        try {
            AudioRecorder r = new AudioRecorder();
            String mode = Storage.txMode(this);
            if (privateRec) mode = "local"; // private recordings never leave the phone
            if ("local".equals(mode)) {
                if (LocalTranscriber.isModelReady(this)) {
                    localSession = new LocalTranscriber.Session(this, Transcriber.stem(file.getName()),
                            AudioRecorder.rate(), Storage.txMinutes(this));
                    // fed below together with the live stream
                } else {
                    main.post(() -> notifyError("Download the on-device model in Settings to transcribe privately."));
                }
            } else if ("groq".equals(mode)) {
                String base = Transcriber.stem(file.getName());
                r.setChunks(this, Transcriber.pendingDir(this), base, Storage.txMinutes(this),
                        chunk -> Transcriber.get(this).chunkReady());
            }
            if (streamRec) {
                try {
                    streamer = new RtmpStreamer(Storage.streamUrl(this), Storage.streamKey(this),
                            Storage.streamKbps(this), (st, detail) -> main.post(() -> onStream(st, detail)));
                } catch (IllegalArgumentException bad) {
                    streamer = null;
                    final String msg = bad.getMessage();
                    main.post(() -> notifyError("Live stream not started: " + msg));
                }
            }
            final LocalTranscriber.Session lsF = localSession;
            final RtmpStreamer stF = streamer;
            if (lsF != null || stF != null) r.setSink((pcm, n) -> {
                if (lsF != null) lsF.feed(pcm, n);
                if (stF != null) stF.feed(pcm, n);
            });
            r.setErrorListener(err -> main.post(() -> {
                if (recording && sessionId == thisSession) {
                    notifyError("Recording stopped due to a storage or audio error.");
                    stop();
                }
            }));
            r.start(file, kbps, useMp3);
            recorder = r;
            if (streamer != null) streamer.start();
        } catch (Exception e) {
            abandonFocus();
            if (localSession != null) { localSession.finish(); localSession = null; }
            stopStream();
            if (file != null) file.delete();
            file = null;
            finishService();
            notifyError("Could not start recording. The mic may be in use by another app, for example a call.");
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "naad:recording");
            wakeLock.acquire(6 * 60 * 60 * 1000L); // safety limit: 6 hours
        }

        if (privateRec) Storage.prefs(this).edit()
                .putBoolean("private:" + Transcriber.stem(file.getName()), true).apply();
        recording = true;
        paused = false;
        pausedByFocus = false;
        startedAt = SystemClock.elapsedRealtime();
        pausedTotal = 0;
        main.removeCallbacks(spaceCheck);
        main.postDelayed(spaceCheck, SPACE_CHECK_MS);
        updateNotification();
        notifyState();
    }

    public void pause() {
        if (!recording || paused) return;
        if (recorder != null) recorder.pause();
        pauseStart = SystemClock.elapsedRealtime();
        paused = true;
        updateNotification();
        notifyState();
    }

    public void resume() {
        if (!recording || !paused) return;
        if (recorder != null) recorder.resume();
        pausedTotal += SystemClock.elapsedRealtime() - pauseStart;
        paused = false;
        pausedByFocus = false;
        updateNotification();
        notifyState();
    }

    public void stop() {
        if (!recording) { finishService(); return; }
        final int stoppingSession = sessionId;
        main.removeCallbacks(spaceCheck);
        File f = file;
        boolean ok;
        try {
            if (recorder != null) recorder.stop();
            ok = f != null && f.length() > 1024;
        } catch (Exception e) {
            // Keep partial recording if substantial audio was written before an error
            ok = f != null && f.length() > 8192;
        } finally {
            recorder = null;
            recording = false;
            paused = false;
            pausedByFocus = false;
            file = null;
            abandonFocus();
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (Exception ignored) { }
            }
        }
        final RtmpStreamer stS = streamer;
        streamer = null;
        streamState = null;
        if (!ok && stS != null) new Thread(stS::stop, "rtmp-stop").start();
        if (!ok) {
            if (localSession != null) { localSession.finish(); localSession = null; }
            if (f != null) {
                Transcriber.get(this).deletePending(Transcriber.stem(f.getName()));
                f.delete();
            }
            finishService();
            notifyError("Recording too short, discarded");
            notifyState();
            return;
        }
        lastSaved = f;
        notifyState();
        final File saved = f;
        final LocalTranscriber.Session ls = localSession;
        localSession = null;
        new Thread(() -> {
            boolean copied = Storage.copyToFolder(this, saved);
            if (ls != null) ls.finish();
            if (stS != null) stS.stop(); // delivers any audio still buffered, up to a minute
            main.post(() -> {
                if (listener != null) listener.onSaved(saved, copied);
                // Guard against killing a newer recording started while cleanup ran
                if (!recording && sessionId == stoppingSession) {
                    finishService();
                }
            });
        }, "rec-finalize").start();
    }

    /** null when not streaming, otherwise one of the RtmpStreamer states. */
    public String streamState() { return streamState; }
    public String streamDetail() { return streamDetail; }

    private void onStream(String st, String detail) {
        if (!recording) return;
        String prev = streamState;
        streamState = st;
        streamDetail = detail;
        if (RtmpStreamer.FAILED.equals(st)) notifyError("Live stream stopped: " + detail + ". Recording continues.");
        else if (RtmpStreamer.RETRYING.equals(st) && (RtmpStreamer.LIVE.equals(prev) || RtmpStreamer.CATCHING_UP.equals(prev)))
            notifyError("Internet lost. Audio is buffered and will be sent when it reconnects.");
        else if (RtmpStreamer.LIVE.equals(st) && RtmpStreamer.CATCHING_UP.equals(prev))
            notifyError("Stream caught up. Nothing was lost.");
        updateNotification();
        notifyState();
    }

    private void stopStream() {
        if (streamer != null) { final RtmpStreamer s = streamer; streamer = null; new Thread(s::stop).start(); }
        streamState = null;
    }

    public File takeLastSaved() { File f = lastSaved; lastSaved = null; return f; }
    public String takePendingError() { String e = pendingError; pendingError = null; return e; }

    private void requestFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setOnAudioFocusChangeListener(focusListener, main)
                .build();
        audioManager.requestAudioFocus(focusRequest);
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            try { audioManager.abandonAudioFocusRequest(focusRequest); } catch (Exception ignored) { }
            focusRequest = null;
        }
    }

    private void finishService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void notifyState() { if (listener != null) listener.onStateChanged(); }

    private void notifyError(String m) {
        if (listener != null) {
            listener.onError(m);
        } else {
            pendingError = m;
            showAlertNotification(m);
        }
    }

    // ---------- Notification ----------
    private boolean goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Recording",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while a recording is in progress");
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
            return true;
        } catch (SecurityException | IllegalStateException e) {
            return false;
        }
    }

    private void showAlertNotification(String message) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(ALERT_CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(ALERT_CHANNEL, "Recording alerts",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 4, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setColor(0xFFE8771E)
                .setAutoCancel(true)
                .setContentIntent(content)
                .build();
        try { nm.notify(ALERT_NOTIF_ID, n); } catch (SecurityException ignored) { }
    }

    private void updateNotification() {
        if (!recording) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        try { nm.notify(NOTIF_ID, buildNotification()); } catch (SecurityException ignored) { }
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, RecorderService.class).setAction(a);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = paused
                ? (pausedByFocus ? "Paused (another app using audio)" : "Recording paused")
                : "Recording";
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText((RtmpStreamer.LIVE.equals(streamState) ? "LIVE  \u00b7  "
                        : RtmpStreamer.CATCHING_UP.equals(streamState) ? "LIVE, catching up  \u00b7  "
                        : RtmpStreamer.RETRYING.equals(streamState) ? "Offline, buffering stream  \u00b7  "
                        : streamState != null && !RtmpStreamer.STOPPED.equals(streamState) ? "Stream connecting  \u00b7  " : "")
                        + (file != null ? file.getName() : getString(R.string.app_name)))
                .setColor(0xFFE8771E)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(content);
        if (recording && !paused) {
            b.setUsesChronometer(true).setShowWhen(true)
                    .setWhen(System.currentTimeMillis() - elapsed());
        }
        if (recording) {
            if (paused) b.addAction(R.drawable.ic_notif_play, "Resume", action(ACTION_RESUME, 2));
            else b.addAction(R.drawable.ic_notif_pause, "Pause", action(ACTION_PAUSE, 1));
            b.addAction(R.drawable.ic_notif_stop, "Stop and save", action(ACTION_STOP, 3));
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(spaceCheck);
        if (recording) stop();
        super.onDestroy();
    }
}
