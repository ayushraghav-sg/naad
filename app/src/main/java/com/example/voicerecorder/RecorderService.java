package com.example.voicerecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
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

    private static final String CHANNEL = "recording";
    private static final int NOTIF_ID = 7;

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

    private MediaRecorder mr;
    private Mp3Recorder mp3;
    private LocalTranscriber.Session localSession;
    private boolean privateRec;
    private File file;
    private boolean recording, paused;
    private long startedAt, pausedTotal, pauseStart;
    private PowerManager.WakeLock wakeLock;
    private File lastSaved;

    @Override public IBinder onBind(Intent i) { return binder; }

    public void setListener(Listener l) { listener = l; }
    public boolean isRecording() { return recording; }
    public boolean isPaused() { return paused; }
    public File currentFile() { return file; }

    public long elapsed() {
        if (!recording) return 0;
        long now = paused ? pauseStart : SystemClock.elapsedRealtime();
        return now - startedAt - pausedTotal;
    }

    public float level() {
        if (!recording || paused) return 0;
        if (mp3 != null) return mp3.level();
        try { return mr != null ? mr.getMaxAmplitude() / 32767f : 0; } catch (Exception e) { return 0; }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(a)) {
            privateRec = intent.getBooleanExtra(EXTRA_PRIVATE, false);
            start(intent.getBooleanExtra(EXTRA_MP3, true), intent.getIntExtra(EXTRA_KBPS, 128));
        } else if (ACTION_PAUSE.equals(a)) {
            pause();
        } else if (ACTION_RESUME.equals(a)) {
            resume();
        } else if (ACTION_STOP.equals(a)) {
            stop();
        }
        return START_NOT_STICKY;
    }

    // ---------- Recording ----------
    private void start(boolean useMp3, int kbps) {
        if (recording) return;
        file = new File(Storage.recDir(this), Storage.newName(this, useMp3));

        // Must go foreground right away (Android requirement for microphone services)
        goForeground();
        try {
            if (useMp3) {
                Mp3Recorder m = new Mp3Recorder();
                String mode = Storage.txMode(this);
                if (privateRec) mode = "local"; // private recordings never leave the phone
                if ("local".equals(mode)) {
                    if (LocalTranscriber.isModelReady(this)) {
                        localSession = new LocalTranscriber.Session(this, Transcriber.stem(file.getName()),
                                Mp3Recorder.rate(), Storage.txMinutes(this));
                        final LocalTranscriber.Session ls = localSession;
                        m.setSink(ls::feed);
                    } else {
                        main.post(() -> notifyError("Download the on-device model in Settings to transcribe privately."));
                    }
                } else if ("groq".equals(mode)) {
                    String base = Transcriber.stem(file.getName());
                    m.setChunks(this, Transcriber.pendingDir(this), base, Storage.txMinutes(this),
                            chunk -> Transcriber.get(this).chunkReady());
                }
                m.start(file, kbps);
                mp3 = m;
            } else {
                if (!"off".equals(Storage.txMode(this)) || privateRec)
                    main.post(() -> notifyError("Transcription works with MP3 only. Recording M4A without transcript."));
                MediaRecorder r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? new MediaRecorder(this) : new MediaRecorder();
                try {
                    r.setAudioSource(MediaRecorder.AudioSource.MIC);
                    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    r.setAudioSamplingRate(44100);
                    r.setAudioEncodingBitRate(kbps * 1000);
                    r.setAudioChannels(1);
                    r.setOutputFile(file.getAbsolutePath());
                    r.prepare();
                    r.start();
                } catch (Exception e) {
                    r.release();
                    throw e;
                }
                mr = r;
            }
        } catch (Exception e) {
            if (localSession != null) { localSession.finish(); localSession = null; }
            file.delete();
            file = null;
            finishService();
            notifyError("Could not start recording. The mic may be in use by another app, for example a call.");
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "naad:recording");
        wakeLock.acquire(6 * 60 * 60 * 1000L); // safety limit: 6 hours

        if (privateRec) Storage.prefs(this).edit()
                .putBoolean("private:" + Transcriber.stem(file.getName()), true).apply();
        recording = true;
        paused = false;
        startedAt = SystemClock.elapsedRealtime();
        pausedTotal = 0;
        updateNotification();
        notifyState();
    }

    public void pause() {
        if (!recording || paused) return;
        try {
            if (mp3 != null) mp3.pause(); else mr.pause();
            pauseStart = SystemClock.elapsedRealtime();
            paused = true;
        } catch (Exception e) {
            notifyError("Pause is not supported on this device");
        }
        updateNotification();
        notifyState();
    }

    public void resume() {
        if (!recording || !paused) return;
        try {
            if (mp3 != null) mp3.resume(); else mr.resume();
            pausedTotal += SystemClock.elapsedRealtime() - pauseStart;
            paused = false;
        } catch (Exception e) {
            notifyError("Could not resume");
        }
        updateNotification();
        notifyState();
    }

    public void stop() {
        if (!recording) { finishService(); return; }
        File f = file;
        boolean ok;
        try {
            if (mp3 != null) {
                mp3.stop();
            } else {
                if (paused) mr.resume();
                mr.stop();
            }
            ok = f != null && f.length() > 1024;
        } catch (Exception e) {
            ok = false;
        } finally {
            if (mr != null) mr.release();
            mr = null;
            mp3 = null;
            recording = false;
            paused = false;
            file = null;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        }
        if (!ok) {
            if (localSession != null) { localSession.finish(); localSession = null; }
            if (f != null) f.delete();
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
            main.post(() -> {
                if (listener != null) listener.onSaved(saved, copied);
                finishService();
            });
        }).start();
    }

    public File takeLastSaved() { File f = lastSaved; lastSaved = null; return f; }

    private void finishService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void notifyState() { if (listener != null) listener.onStateChanged(); }

    private void notifyError(String m) {
        if (listener != null) listener.onError(m);
    }

    // ---------- Notification ----------
    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Recording",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while a recording is in progress");
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification() {
        if (!recording) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification());
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

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(paused ? "Recording paused" : "Recording")
                .setContentText(file != null ? file.getName() : getString(R.string.app_name))
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
        if (recording) stop();
        super.onDestroy();
    }
}
