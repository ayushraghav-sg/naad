package com.example.voicerecorder;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity
        implements RecorderService.Listener, Transcriber.Listener,
        PlaybackController.Listener, SettingsSheet.Host {

    private static final String[] SORT_NAMES = {"Newest", "Oldest", "Name", "Longest"};
    private static final String EMPTY_DEFAULT =
            "No recordings yet.\nTap the saffron button below to capture your first one. Recording continues even if you leave the app.";

    private RecorderService svc;
    private PlaybackController playback;
    private RecordingsAdapter adapter;
    private SettingsSheet settingsSheet;
    private File lastSavedFile;
    private boolean useMp3 = true;
    private int quality = 1; // 0 = Voice (64 kbps), 1 = High (128 kbps)
    private boolean seeking = false;
    private boolean privateNext = false;
    private int sortMode = 0; // 0 newest, 1 oldest, 2 name, 3 longest
    private String searchQuery = "";
    private int ticks = 0;
    private boolean tickScheduled = false;

    private ImageButton btnRecord, btnPause, btnSettings, btnSort, btnClearSearch;
    private View notifBar, transcriptBox, savedBar, optionsRow, optionsRow2, searchBar;
    private TextView txtFree, txtTranscript, transcriptLabel, txtSort, txtEmpty;
    private TextView txtTimer, txtState, txtFolder, txtListHeader;
    private TextView chipMp3, chipM4a, chipVoice, chipHigh, chipTx, chipPrivate;
    private EditText edSearch;
    private WaveformView wave;
    private ListView list;
    private NaadDialog approvalDialog;

    private TextView liveTv;
    private String liveBase;
    private ScrollView liveScroll;

    private SharedPreferences prefs;
    private final List<RecordingsAdapter.Item> allItems = new ArrayList<>();
    private final Map<String, Long> durationCache = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger listGeneration = new AtomicInteger(0);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable hideSaved = () -> {
        if (savedBar != null) savedBar.setVisibility(View.GONE);
    };

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), res -> {
                updateNotifBar();
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    startRecording();
                } else {
                    toast("Microphone permission is needed to record");
                }
            });

    private final ActivityResultLauncher<Uri> audioFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), tree -> {
                if (tree == null) return;
                persistTreePermission(tree);
                prefs.edit().putString("folder", tree.toString()).apply();
                txtFolder.setText(Storage.folderLabel(this));
                if (settingsSheet != null) settingsSheet.refreshFolderLabels();
            });

    private final ActivityResultLauncher<Uri> txFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), tree -> {
                if (tree == null) return;
                persistTreePermission(tree);
                prefs.edit().putString("txFolder", tree.toString()).apply();
                if (settingsSheet != null) settingsSheet.refreshFolderLabels();
            });

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean rec = svc != null && svc.isRecording();
            boolean play = playback != null && playback.isPlaying();
            if (!rec && !play) {
                tickScheduled = false;
                txtTimer.setAlpha(1f);
                return;
            }
            ticks++;
            boolean blinkOn = (ticks / 8) % 2 == 0;
            if (rec) {
                if (!svc.isPaused()) {
                    wave.add((float) Math.sqrt(svc.level()));
                    txtState.setText(blinkOn ? "\u25CF  REC" : "     REC");
                    txtTimer.setAlpha(1f);
                } else {
                    txtTimer.setAlpha(blinkOn ? 1f : 0.35f);
                }
                txtTimer.setText(Formatters.fmtDuration(svc.elapsed()));
            } else {
                txtTimer.setAlpha(1f);
            }
            if (play && !seeking) {
                adapter.updateVisiblePlayback(list);
            }
            ui.postDelayed(this, 60);
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((RecorderService.LocalBinder) b).get();
            svc.setListener(MainActivity.this);
            String err = svc.takePendingError();
            if (err != null) toast(err);
            File saved = svc.takeLastSaved();
            if (saved != null) showSaved(saved);
            renderRecordingState();
            refreshList();
            ensureTicking();
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.indigo));
        }
        WindowInsetsControllerCompat ic = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (ic != null) ic.setAppearanceLightStatusBars(false);

        View topBar = findViewById(R.id.topBar);
        View bottomBar = findViewById(R.id.bottomBar);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            topBar.setPadding(sys.left, sys.top, sys.right, 0);
            bottomBar.setPadding(sys.left, 0, sys.right, sys.bottom);
            return insets;
        });

        prefs = Storage.prefs(this);
        File oldModel = new File(getFilesDir(), "vosk-en");
        if (oldModel.exists() && !LocalTranscriber.isReady(this, "small")) {
            oldModel.renameTo(LocalTranscriber.modelDir(this, "small"));
        }
        quality = prefs.getInt("quality", 1);
        useMp3 = prefs.getBoolean("mp3", true);
        privateNext = prefs.getBoolean("privateNext", false);
        sortMode = prefs.getInt("sort", 0);

        playback = new PlaybackController(this, this);
        settingsSheet = new SettingsSheet(this, this);

        btnRecord = findViewById(R.id.btnRecord);
        btnPause = findViewById(R.id.btnPause);
        btnSettings = findViewById(R.id.btnSettings);
        btnSort = findViewById(R.id.btnSort);
        notifBar = findViewById(R.id.notifBar);
        txtFree = findViewById(R.id.txtFree);
        txtTranscript = findViewById(R.id.txtTranscript);
        transcriptLabel = findViewById(R.id.txtTranscriptLabel);
        transcriptBox = findViewById(R.id.transcriptBox);
        chipTx = findViewById(R.id.chipTx);
        chipPrivate = findViewById(R.id.chipPrivate);
        txtSort = findViewById(R.id.txtSort);
        txtTimer = findViewById(R.id.txtTimer);
        txtState = findViewById(R.id.txtState);
        txtFolder = findViewById(R.id.txtFolder);
        txtListHeader = findViewById(R.id.txtListHeader);
        txtEmpty = findViewById(R.id.empty);
        chipMp3 = findViewById(R.id.chipMp3);
        chipM4a = findViewById(R.id.chipM4a);
        chipVoice = findViewById(R.id.chipVoice);
        chipHigh = findViewById(R.id.chipHigh);
        savedBar = findViewById(R.id.savedBar);
        optionsRow = findViewById(R.id.optionsRow);
        optionsRow2 = findViewById(R.id.optionsRow2);
        searchBar = findViewById(R.id.searchBar);
        edSearch = findViewById(R.id.edSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        wave = findViewById(R.id.wave);
        list = findViewById(R.id.list);
        list.setEmptyView(txtEmpty);

        adapter = new RecordingsAdapter(this, playback, new RecordingsAdapter.Callbacks() {
            @Override public void onPlayToggle(File f) { togglePlay(f); }
            @Override public void onMoreClick(View anchor, RecordingsAdapter.Item item) { itemMenu(item); }
            @Override public void onTranscriptClick(File f) { openTranscript(Transcriber.stem(f.getName())); }
            @Override public void onSkip(int deltaMs) {
                playback.skip(deltaMs);
                adapter.updateVisiblePlayback(list);
            }
            @Override public void onSeek(int positionMs) { playback.seekTo(positionMs); }
            @Override public void onSeekingChanged(boolean s) { seeking = s; }
            @Override public void onCycleSpeed() { playback.cycleSpeed(); }
        });
        list.setAdapter(adapter);

        chipTx.setOnClickListener(v -> settingsSheet.show());
        chipPrivate.setOnClickListener(v -> {
            if (svc != null && svc.isRecording()) return;
            privateNext = !privateNext;
            prefs.edit().putBoolean("privateNext", privateNext).apply();
            renderTxChip();
            if (privateNext) toast(LocalTranscriber.isModelReady(this)
                    ? "Private: transcribed on this phone only"
                    : "Private: nothing leaves the phone. Download the model in Settings for transcripts.");
        });
        txtSort.setOnClickListener(v -> sortMenu());
        btnSort.setOnClickListener(v -> sortMenu());
        transcriptBox.setOnClickListener(v -> {
            String b0 = currentBase();
            if (b0 != null) openTranscript(b0);
        });
        findViewById(R.id.btnNotifOn).setOnClickListener(v -> openNotificationSettings());

        btnRecord.setOnClickListener(v -> {
            buzz(v);
            if (svc != null && svc.isRecording()) sendToService(RecorderService.ACTION_STOP);
            else checkPermissionsAndRecord();
        });
        btnPause.setOnClickListener(v -> {
            if (svc == null || !svc.isRecording()) return;
            buzz(v);
            if (svc.isPaused()) svc.resume(); else svc.pause();
        });
        btnSettings.setOnClickListener(v -> settingsSheet.show());
        txtFolder.setOnClickListener(v -> settingsSheet.show());

        chipMp3.setOnClickListener(v -> { useMp3 = true; savePrefs(); });
        chipM4a.setOnClickListener(v -> { useMp3 = false; savePrefs(); });
        chipVoice.setOnClickListener(v -> { quality = 0; savePrefs(); });
        chipHigh.setOnClickListener(v -> { quality = 1; savePrefs(); });
        findViewById(R.id.btnSavedShare).setOnClickListener(v -> { if (lastSavedFile != null) shareMenu(lastSavedFile); });
        findViewById(R.id.btnSavedWa).setOnClickListener(v -> { if (lastSavedFile != null) shareWhatsAppDocument(lastSavedFile); });
        findViewById(R.id.btnSavedRename).setOnClickListener(v -> { if (lastSavedFile != null) rename(lastSavedFile); });

        edSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                btnClearSearch.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
                applyFilter();
            }
        });
        btnClearSearch.setOnClickListener(v -> edSearch.setText(""));

        renderChips();
        renderTxChip();
        txtFolder.setText(Storage.folderLabel(this));
        txtFree.setText(freeText());
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, RecorderService.class), conn, Context.BIND_AUTO_CREATE);
        refreshList();
        updateNotifBar();
        Transcriber.get(this).setListener(this);
        checkPending();
        ensureTicking();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tick);
        tickScheduled = false;
        if (svc != null) svc.setListener(null);
        Transcriber.get(this).setListener(null);
        try { unbindService(conn); } catch (Exception ignored) { }
        svc = null;
        // Playback intentionally continues while screen locks or user switches apps
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (playback != null) playback.release();
        io.shutdownNow();
        super.onDestroy();
    }

    private void ensureTicking() {
        boolean need = (svc != null && svc.isRecording()) || (playback != null && playback.isPlaying());
        if (need && !tickScheduled) {
            tickScheduled = true;
            ui.post(tick);
        }
    }

    private void persistTreePermission(Uri tree) {
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) { }
    }

    // ---------- SettingsSheet.Host ----------
    @Override public boolean isUsingMp3() { return useMp3; }
    @Override public void onPickAudioFolder() { audioFolderLauncher.launch(null); }
    @Override public void onPickTranscriptFolder() { txFolderLauncher.launch(null); }
    @Override public void onSettingsClosed() {
        txtFolder.setText(Storage.folderLabel(this));
        renderTxChip();
    }

    // ---------- PlaybackController.Listener ----------
    @Override public void onPlaybackChanged() {
        adapter.notifyDataSetChanged();
        ensureTicking();
    }

    @Override public void onPlaybackError(String message) {
        toast(message);
        adapter.notifyDataSetChanged();
    }

    // ---------- Prefs & chips ----------
    private void savePrefs() {
        if (svc != null && svc.isRecording()) return;
        prefs.edit().putBoolean("mp3", useMp3).putInt("quality", quality).apply();
        renderChips();
        if (txtFree != null) txtFree.setText(freeText());
    }

    private void renderChips() {
        chipMp3.setBackgroundResource(useMp3 ? R.drawable.bg_chip : R.drawable.bg_chip_off);
        chipM4a.setBackgroundResource(useMp3 ? R.drawable.bg_chip_off : R.drawable.bg_chip);
        chipVoice.setBackgroundResource(quality == 0 ? R.drawable.bg_chip : R.drawable.bg_chip_off);
        chipHigh.setBackgroundResource(quality == 1 ? R.drawable.bg_chip : R.drawable.bg_chip_off);
        chipVoice.setContentDescription("Voice quality, 64 kbps, smaller files");
        chipHigh.setContentDescription("High quality, 128 kbps");
    }

    // ---------- Permissions ----------
    private void checkPermissionsAndRecord() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) startRecording();
        else permLauncher.launch(need.toArray(new String[0]));
    }

    // ---------- Recording (via service) ----------
    private void startRecording() {
        if (Storage.freeBytes(this) < RecorderService.LOW_SPACE * 2) {
            toast("Not enough storage to record. Free up some space.");
            return;
        }
        playback.stop();
        adapter.notifyDataSetChanged();
        Intent i = new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_START)
                .putExtra(RecorderService.EXTRA_MP3, useMp3)
                .putExtra(RecorderService.EXTRA_KBPS, quality == 1 ? 128 : 64)
                .putExtra(RecorderService.EXTRA_PRIVATE, privateNext);
        ContextCompat.startForegroundService(this, i);
        savedBar.setVisibility(View.GONE);
        ui.removeCallbacks(hideSaved);
        txtTranscript.setText("");
        transcriptBox.setVisibility(View.GONE);
        wave.clear();
        wave.setVisibility(View.VISIBLE);
        ensureTicking();
    }

    private void sendToService(String action) {
        startService(new Intent(this, RecorderService.class).setAction(action));
    }

    private void renderRecordingState() {
        boolean rec = svc != null && svc.isRecording();
        boolean paused = rec && svc.isPaused();
        btnRecord.setBackgroundResource(rec ? R.drawable.bg_rec_active : R.drawable.bg_rec_idle);
        btnRecord.setImageResource(rec ? R.drawable.ic_stop : R.drawable.ic_mic);
        btnRecord.setContentDescription(rec ? "Stop and save" : "Record");
        btnPause.setVisibility(rec ? View.VISIBLE : View.GONE);
        btnSort.setVisibility(rec ? View.INVISIBLE : View.VISIBLE);
        btnPause.setImageResource(paused ? R.drawable.ic_resume : R.drawable.ic_pause);
        btnPause.setContentDescription(paused ? "Resume" : "Pause");
        optionsRow.setAlpha(rec ? 0.4f : 1f);
        optionsRow2.setAlpha(rec ? 0.4f : 1f);
        wave.setActive(rec && !paused);
        wave.setVisibility(rec || savedBar.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        if (rec) {
            savedBar.setVisibility(View.GONE);
            txtState.setText(paused ? "PAUSED" : "\u25CF  REC");
            File cur = svc.currentFile();
            if (cur != null) txtFree.setText(cur.getName());
            txtState.setTextColor(ContextCompat.getColor(this,
                    paused ? R.color.muted : R.color.rec_red));
            txtTimer.setText(Formatters.fmtDuration(svc.elapsed()));
        } else {
            txtState.setTextColor(ContextCompat.getColor(this, R.color.muted));
            if (savedBar.getVisibility() != View.VISIBLE) {
                txtState.setText("Ready to record");
                txtTimer.setText("00:00");
            }
            txtFree.setText(freeText());
        }
    }

    @Override public void onStateChanged() {
        renderRecordingState();
        ensureTicking();
    }

    @Override public void onError(String message) {
        toast(message);
        renderRecordingState();
    }

    @Override public void onSaved(File file, boolean copied) {
        showSaved(file);
        if (Storage.folderUri(this) != null && !copied) {
            toast("Saved in app, but could not copy to your folder. Choose it again in Settings.");
        }
    }

    private void showSaved(File f) {
        lastSavedFile = f;
        txtState.setTextColor(ContextCompat.getColor(this, R.color.muted));
        txtState.setText(f.getName());
        savedBar.setVisibility(View.VISIBLE);
        wave.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideSaved);
        ui.postDelayed(hideSaved, 20000);
        io.execute(() -> {
            long dur = durationOf(f);
            ui.post(() -> {
                if (f.equals(lastSavedFile) && (svc == null || !svc.isRecording())) {
                    txtTimer.setText(Formatters.fmtDuration(dur));
                }
            });
        });
        refreshList();
    }

    private void sortMenu() {
        NaadDialog d = new NaadDialog(this, NaadDialog.SHEET).title("Sort recordings");
        String[] names = {"Newest first", "Oldest first", "Name A to Z", "Longest first"};
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            d.item(names[i], sortMode == i ? R.drawable.ic_check : 0, false, () -> {
                sortMode = mode;
                prefs.edit().putInt("sort", sortMode).apply();
                refreshList();
            });
        }
        d.show();
    }

    private void updateNotifBar() {
        boolean allowed = NotificationManagerCompat.from(this).areNotificationsEnabled();
        notifBar.setVisibility(allowed ? View.GONE : View.VISIBLE);
    }

    private void openNotificationSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try { startActivity(i); }
        catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    // ---------- Transcription ----------
    private String currentBase() {
        if (svc != null && svc.isRecording() && svc.currentFile() != null)
            return Transcriber.stem(svc.currentFile().getName());
        return lastSavedFile != null ? Transcriber.stem(lastSavedFile.getName()) : null;
    }

    private void renderTxChip() {
        String mode = Storage.txMode(this);
        int m = Storage.txMinutes(this);
        String every = m < 0 ? "full" : "every " + m + " min";
        String label;
        if (privateNext) label = LocalTranscriber.isModelReady(this) ? "On device  \u00b7  " + every : "Private: no transcript";
        else if ("groq".equals(mode)) label = "Groq  \u00b7  " + every;
        else if ("local".equals(mode)) label = "On device  \u00b7  " + every;
        else label = "Transcribe: Off";
        chipTx.setText(label);
        chipTx.setBackgroundResource("off".equals(mode) && !privateNext ? R.drawable.bg_chip_off : R.drawable.bg_chip);
        chipPrivate.setBackgroundResource(privateNext ? R.drawable.bg_chip : R.drawable.bg_chip_off);
        chipPrivate.setContentDescription(privateNext ? "Private on: transcribe on this phone only" : "Private off");
    }

    private void showTranscriptBox(String label, String text, boolean muted) {
        transcriptLabel.setText(label);
        txtTranscript.setTextColor(ContextCompat.getColor(this, muted ? R.color.muted : R.color.text));
        txtTranscript.setText(text);
        transcriptBox.setVisibility(View.VISIBLE);
    }

    @Override public void onTranscript(String base, String text) {
        File all = new File(Storage.recDir(this), base + ".txt");
        io.execute(() -> {
            String fileText = readText(all);
            String full = fileText.length() >= text.length() ? fileText : text;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (base.equals(liveBase) && liveTv != null) {
                    liveTv.setText(full.isEmpty() ? "Listening..." : full);
                    if (liveScroll != null) liveScroll.post(() -> liveScroll.fullScroll(View.FOCUS_DOWN));
                }
                if (base.equals(currentBase()) && !full.isEmpty()) {
                    String t = full.length() > 400 ? "..." + full.substring(full.length() - 400) : full;
                    boolean live = svc != null && svc.isRecording();
                    showTranscriptBox(live ? "TRANSCRIPT  \u00b7  LIVE  \u00b7  TAP FOR FULL VIEW" : "TRANSCRIPT  \u00b7  TAP TO OPEN", t, false);
                }
                if (svc == null || !svc.isRecording()) refreshList();
            });
        });
    }

    @Override public void onStatus(String status) {
        if (status == null) return;
        if (transcriptBox.getVisibility() != View.VISIBLE || txtTranscript.getText().length() == 0
                || txtTranscript.getCurrentTextColor() == ContextCompat.getColor(this, R.color.muted)) {
            showTranscriptBox("TRANSCRIPT", status, true);
        }
    }

    @Override public void onNeedsApproval(int waiting) {
        if (Transcriber.get(this).isOnline()) showApprovalDialog(waiting);
    }

    private void checkPending() {
        Transcriber t = Transcriber.get(this);
        int n = t.pending().size();
        if (n == 0) return;
        if (t.needsApproval()) { if (t.isOnline()) showApprovalDialog(n); }
        else t.kick();
    }

    private void showApprovalDialog(int n) {
        if (approvalDialog != null && approvalDialog.isShowing()) return;
        String parts = n + (n == 1 ? " part was" : " parts were");
        NaadDialog d = new NaadDialog(this, false)
                .title("Send for transcription?")
                .message(parts + " recorded while you were offline. Send "
                        + (n == 1 ? "it" : "them") + " to Groq now, or transcribe on this phone instead.");
        d.button("Later", NaadDialog.SECONDARY, null);
        if (LocalTranscriber.isModelReady(this))
            d.button("On device", NaadDialog.SECONDARY, () -> LocalTranscriber.transcribePending(this));
        d.button("Send", NaadDialog.PRIMARY, () -> Transcriber.get(this).approveAndSend());
        TextView discard = new TextView(this);
        discard.setText("Don't transcribe these parts");
        discard.setTextColor(ContextCompat.getColor(this, R.color.muted));
        discard.setPadding(0, dp(4), 0, dp(4));
        discard.setOnClickListener(v -> {
            approvalDialog.dismiss();
            new NaadDialog(this, false).title("Discard waiting parts?")
                    .message("Your recordings stay. Only the waiting transcription is cancelled.")
                    .button("Keep", NaadDialog.SECONDARY, null)
                    .button("Discard", NaadDialog.DANGER, () -> Transcriber.get(this).discardAll())
                    .show();
        });
        d.view(discard, false);
        approvalDialog = d.show();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void transcribeWithGroq(File f) {
        String key = Storage.groqKey(this);
        if (key.isEmpty()) { toast("Add your Groq API key in Settings first"); settingsSheet.show(); return; }
        if (!Transcriber.get(this).isOnline()) { toast("No internet. Try again when online."); return; }
        lastSavedFile = f;
        Transcriber.get(this).queueWholeFile(f);
        toast("Sending to Groq...");
    }

    private void transcribeOnDevice(File f) {
        if (!LocalTranscriber.isModelReady(this)) {
            toast("Download the on-device model in Settings first");
            settingsSheet.show();
            return;
        }
        lastSavedFile = f;
        LocalTranscriber.transcribeFile(this, f);
        toast("Transcribing on this phone...");
    }

    /** Full-screen, scrollable transcript. Loads off-UI-thread and updates live while recording. */
    private void openTranscript(String base) {
        boolean recordingThis = svc != null && svc.isRecording() && base.equals(currentBase());
        String initial = "";
        if (base.equals(currentBase()) && txtTranscript.getText().length() > 0
                && txtTranscript.getCurrentTextColor() != ContextCompat.getColor(this, R.color.muted)) {
            initial = txtTranscript.getText().toString();
        }

        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text));
        tv.setTextSize(17);
        tv.setLineSpacing(0, 1.35f);
        tv.setText(initial.isEmpty() ? (recordingThis ? "Listening..." : "Loading...") : initial);
        int pad = dp(16);
        tv.setPadding(pad, pad, pad, pad);
        tv.setBackgroundResource(R.drawable.bg_card);

        NaadDialog d = new NaadDialog(this, NaadDialog.FULL).title(recordingThis ? "Live transcript" : "Transcript");
        TextView sub = new TextView(this);
        sub.setText(Formatters.displayName(base + ".mp3") + (recordingThis ? "  \u00b7  updating as you speak" : ""));
        sub.setTextColor(ContextCompat.getColor(this, R.color.muted));
        sub.setTextSize(13);
        sub.setPadding(dp(4), 0, 0, dp(12));
        d.view(sub, false).view(tv, false);
        d.button("Copy", NaadDialog.SECONDARY, true, () -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Transcript", tv.getText()));
                toast("Copied");
            }
        });
        d.button("Share", NaadDialog.PRIMARY, true, () -> shareText(tv.getText().toString()));
        liveBase = base;
        liveTv = tv;
        d.onDismiss(() -> { liveTv = null; liveBase = null; liveScroll = null; });
        d.show();

        View p = tv.getParent() instanceof View ? (View) tv.getParent() : null;
        while (p != null && !(p instanceof ScrollView))
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        liveScroll = (ScrollView) p;

        File txt = new File(Storage.recDir(this), base + ".txt");
        io.execute(() -> {
            String loaded = readText(txt);
            ui.post(() -> {
                if (liveTv != tv) return;
                if (!loaded.isEmpty()) {
                    tv.setText(loaded);
                } else if ("Loading...".contentEquals(tv.getText())) {
                    tv.setText(recordingThis ? "Listening..." : "No speech was found in this recording.");
                }
                if (recordingThis && liveScroll != null) {
                    liveScroll.post(() -> liveScroll.fullScroll(View.FOCUS_DOWN));
                }
            });
        });
    }

    // ---------- Recordings List & Search ----------
    private void refreshList() {
        final int gen = listGeneration.incrementAndGet();
        final File activeRec = (svc != null && svc.isRecording()) ? svc.currentFile() : null;
        final int currentSort = sortMode;
        io.execute(() -> {
            File dir = Storage.recDir(this);
            File[] arr = dir.listFiles((d, n) -> n.endsWith(".m4a") || n.endsWith(".mp3"));
            List<RecordingsAdapter.Item> built = new ArrayList<>();
            if (arr != null) {
                for (File f : arr) {
                    if (f.equals(activeRec)) continue;
                    String base = Transcriber.stem(f.getName());
                    File txtFile = new File(dir, base + ".txt");
                    boolean hasTxt = txtFile.exists();
                    String txtLower = hasTxt ? readText(txtFile).toLowerCase(Locale.ROOT) : "";
                    boolean priv = prefs.getBoolean("private:" + base, false);
                    long dur = durationOf(f);
                    built.add(new RecordingsAdapter.Item(
                            f, Formatters.displayName(f.getName()), dur, hasTxt, priv, txtLower));
                }
                switch (currentSort) {
                    case 1:
                        built.sort((a, b) -> Long.compare(a.file.lastModified(), b.file.lastModified()));
                        break;
                    case 2:
                        built.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
                        break;
                    case 3:
                        built.sort((a, b) -> Long.compare(b.durationMs, a.durationMs));
                        break;
                    default:
                        built.sort((a, b) -> Long.compare(b.file.lastModified(), a.file.lastModified()));
                        break;
                }
            }
            ui.post(() -> {
                if (gen != listGeneration.get() || isFinishing() || isDestroyed()) return;
                allItems.clear();
                allItems.addAll(built);
                searchBar.setVisibility(allItems.isEmpty() ? View.GONE : View.VISIBLE);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        List<RecordingsAdapter.Item> shown;
        if (searchQuery.isEmpty()) {
            shown = allItems;
            txtEmpty.setText(EMPTY_DEFAULT);
        } else {
            shown = new ArrayList<>();
            for (RecordingsAdapter.Item item : allItems) {
                if (item.displayName.toLowerCase(Locale.ROOT).contains(searchQuery)
                        || item.file.getName().toLowerCase(Locale.ROOT).contains(searchQuery)
                        || item.transcriptLower.contains(searchQuery)) {
                    shown.add(item);
                }
            }
            txtEmpty.setText("No recordings match \"" + edSearch.getText().toString().trim() + "\".");
        }
        txtListHeader.setText(allItems.isEmpty()
                ? "Recordings"
                : (searchQuery.isEmpty()
                        ? "Recordings  (" + allItems.size() + ")"
                        : "Matches  (" + shown.size() + " of " + allItems.size() + ")"));
        txtSort.setText(SORT_NAMES[sortMode] + "  \u25BE");
        txtSort.setVisibility(allItems.size() > 1 ? View.VISIBLE : View.GONE);
        adapter.setItems(shown);
    }

    private void itemMenu(RecordingsAdapter.Item item) {
        File f = item.file;
        NaadDialog d = new NaadDialog(this, NaadDialog.SHEET)
                .title(item.displayName)
                .subtitle(Formatters.fmtDuration(item.durationMs) + "  \u00b7  "
                        + Formatters.fmtSize(f.length()) + "  \u00b7  "
                        + Formatters.fullDate(f.lastModified())
                        + (item.isPrivate ? "  \u00b7  Private" : ""));
        d.item("Share", R.drawable.ic_share, false, () -> shareMenu(f));
        d.item("Send on WhatsApp as document", R.drawable.ic_chat, false, () -> shareWhatsAppDocument(f));
        d.divider();
        if (item.hasTranscript) {
            d.item("View transcript", R.drawable.ic_doc, false, () -> openTranscript(Transcriber.stem(f.getName())));
        }
        if (LocalTranscriber.isModelReady(this)) {
            d.item(item.hasTranscript ? "Re-transcribe on this phone" : "Transcribe on this phone",
                    R.drawable.ic_bolt, false, () -> transcribeOnDevice(f));
        }
        if (!item.isPrivate) {
            d.item(item.hasTranscript ? "Re-transcribe with Groq" : "Transcribe with Groq",
                    R.drawable.ic_bolt, false, () -> transcribeWithGroq(f));
        }
        d.item("Rename", R.drawable.ic_edit, false, () -> rename(f));
        d.item("Save copy to " + Storage.folderLabel(this), R.drawable.ic_folder_small, false, () -> copyToFolder(f));
        d.divider();
        d.item("Delete", R.drawable.ic_delete, true, () -> delete(f));
        d.show();
    }

    private void togglePlay(File f) {
        if (svc != null && svc.isRecording()) { toast("Stop recording to play"); return; }
        playback.toggle(f);
    }

    // ---------- Sharing ----------
    private void shareText(String text) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Share transcript"));
    }

    private void shareFiles(List<File> list, String mime) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : list) uris.add(FileProvider.getUriForFile(this, getPackageName() + ".files", f));
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        i.setType(mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share"));
    }

    private List<File> partsOf(File f) {
        String b0 = Transcriber.stem(f.getName());
        File[] arr = Storage.recDir(this).listFiles((dd, nn) -> nn.startsWith(b0 + "_part") && nn.endsWith(".txt"));
        List<File> out = new ArrayList<>();
        if (arr != null) { Arrays.sort(arr); out.addAll(Arrays.asList(arr)); }
        return out;
    }

    private void shareMenu(File f) {
        File txt = transcriptOf(f);
        boolean hasTxt = txt.exists();
        NaadDialog d = new NaadDialog(this, NaadDialog.SHEET)
                .title("Share").subtitle(Formatters.displayName(f.getName()));
        d.item("Audio file", R.drawable.ic_share, false, () -> share(f));
        if (hasTxt) {
            d.item("Transcript as text", R.drawable.ic_doc, false, () ->
                    io.execute(() -> {
                        String text = readText(txt);
                        ui.post(() -> shareText(text));
                    }));
            d.item("Transcript file (.txt)", R.drawable.ic_doc, false,
                    () -> shareFiles(Collections.singletonList(txt), "text/plain"));
            d.item("Audio and transcript", R.drawable.ic_share, false,
                    () -> shareFiles(Arrays.asList(f, txt), "*/*"));
            List<File> parts = partsOf(f);
            if (parts.size() > 1) {
                d.item("All " + parts.size() + " transcript parts", R.drawable.ic_doc, false,
                        () -> shareFiles(parts, "text/plain"));
            }
        }
        d.show();
    }

    private void share(File f) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(f.getName().endsWith(".mp3") ? "audio/mpeg" : "audio/mp4");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share recording"));
    }

    /** Served through DocProvider with a generic type, so WhatsApp sends it as a document. */
    private void shareWhatsAppDocument(File f) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".docs", f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            i.setPackage(pkg);
            try { startActivity(i); return; } catch (ActivityNotFoundException ignored) { }
        }
        toast("WhatsApp is not installed");
    }

    private void copyToFolder(File f) {
        if (Storage.folderUri(this) == null) { settingsSheet.show(); return; }
        io.execute(() -> {
            boolean ok = Storage.copyToFolder(this, f);
            ui.post(() -> toast(ok ? "Saved to " + Storage.folderLabel(this)
                    : "Could not save. Choose the folder again in Settings."));
        });
    }

    // ---------- Rename / Delete ----------
    private void rename(File f) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        et.setTextColor(ContextCompat.getColor(this, R.color.text));
        et.setText(Formatters.stripExt(f.getName()));
        et.setSelectAllOnFocus(true);
        new NaadDialog(this, false).title("Rename").view(et, false)
                .button("Cancel", NaadDialog.SECONDARY, null)
                .button("Save", NaadDialog.PRIMARY, () -> {
                    String n = Storage.cleanName(et.getText().toString());
                    if (n.isEmpty()) return;
                    String ext = f.getName().substring(f.getName().lastIndexOf('.'));
                    File to = new File(f.getParentFile(), n + ext);
                    if (to.equals(f)) return;
                    if (to.exists()) { toast("A recording with that name exists"); return; }
                    if (playback.isCurrent(f)) playback.stop();
                    String oldBase = Transcriber.stem(f.getName());
                    if (!f.renameTo(to)) { toast("Could not rename"); return; }
                    durationCache.remove(f.getAbsolutePath() + ":" + f.length());
                    File[] txts = Storage.recDir(this).listFiles((dd, nn) ->
                            nn.equals(oldBase + ".txt") || (nn.startsWith(oldBase + "_part") && nn.endsWith(".txt")));
                    if (txts != null) for (File t : txts)
                        t.renameTo(new File(t.getParentFile(), n + t.getName().substring(oldBase.length())));
                    Transcriber.get(this).renamePending(oldBase, n);
                    if (prefs.getBoolean("private:" + oldBase, false))
                        prefs.edit().remove("private:" + oldBase).putBoolean("private:" + n, true).apply();
                    if (f.equals(lastSavedFile)) {
                        lastSavedFile = to;
                        if (savedBar.getVisibility() == View.VISIBLE) txtState.setText(to.getName());
                    }
                    refreshList();
                })
                .show();
        et.requestFocus();
    }

    private void delete(File f) {
        new NaadDialog(this, false).title("Delete recording?")
                .message(Formatters.displayName(f.getName())
                        + " and its transcript will be removed from the app. Copies in your chosen folder stay.")
                .button("Cancel", NaadDialog.SECONDARY, null)
                .button("Delete", NaadDialog.DANGER, () -> {
                    if (playback.isCurrent(f)) playback.stop();
                    durationCache.remove(f.getAbsolutePath() + ":" + f.length());
                    f.delete();
                    String b0 = Transcriber.stem(f.getName());
                    File[] txts = Storage.recDir(this).listFiles((dd, nn) ->
                            nn.equals(b0 + ".txt") || (nn.startsWith(b0 + "_part") && nn.endsWith(".txt")));
                    if (txts != null) for (File t : txts) t.delete();
                    Transcriber.get(this).deletePending(b0);
                    prefs.edit().remove("private:" + b0).apply();
                    if (f.equals(lastSavedFile)) {
                        savedBar.setVisibility(View.GONE);
                        transcriptBox.setVisibility(View.GONE);
                    }
                    refreshList();
                })
                .show();
    }

    // ---------- Helpers ----------
    private File transcriptOf(File f) {
        return new File(Storage.recDir(this), Transcriber.stem(f.getName()) + ".txt");
    }

    private static String readText(File f) {
        if (!f.exists()) return "";
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString(StandardCharsets.UTF_8.name()).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private long durationOf(File f) {
        String key = f.getAbsolutePath() + ":" + f.length();
        Long cached = durationCache.get(key);
        if (cached != null) return cached;
        long d = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            d = s == null ? 0 : Long.parseLong(s);
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        durationCache.put(key, d);
        return d;
    }

    private void buzz(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private String freeText() {
        long free = Storage.freeBytes(this);
        if (free == Long.MAX_VALUE) return "";
        long bytesPerHour = (quality == 1 ? 128 : 64) * 1000L / 8 * 3600;
        long hours = free / bytesPerHour;
        String space = free >= 1024L * 1024 * 1024
                ? String.format(Locale.US, "%.1f GB free", free / (1024f * 1024f * 1024f))
                : (free / (1024 * 1024)) + " MB free";
        String time = hours >= 1000 ? "1000+ hours" : hours + (hours == 1 ? " hour" : " hours");
        return space + "  \u00b7  about " + time + " at " + (quality == 1 ? "High" : "Voice");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
