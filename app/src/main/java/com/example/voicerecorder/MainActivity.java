package com.example.voicerecorder;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.view.HapticFeedbackConstants;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity
        implements RecorderService.Listener, Transcriber.Listener {

    private static final int REQ_PERMS = 101;
    private static final int REQ_FOLDER = 102;
    private static final int REQ_TXFOLDER = 103;

    private RecorderService svc;
    private MediaPlayer player;
    private File playingFile, lastSavedFile;
    private boolean useMp3 = true;
    private int quality = 1; // 0 = Voice, 1 = High
    private boolean seeking = false;

    private ImageButton btnRecord, btnPause, btnSettings, btnSort;
    private View notifBar;
    private TextView txtFree, txtTranscript;
    private NaadDialog approvalDialog;
    private TextView chipTx, chipPrivate, transcriptLabel, txtSort;
    private View transcriptBox;
    private boolean privateNext;
    private int ticks = 0;
    private static final long LOW_SPACE = 50L * 1024 * 1024; // auto-stop below 50 MB
    private int sortMode = 0; // 0 newest, 1 oldest, 2 name, 3 longest
    private final Runnable hideSaved = () -> this.savedBar.setVisibility(View.GONE);
    private TextView txtTimer, txtState, txtFolder, txtListHeader;
    private TextView chipMp3, chipM4a, chipVoice, chipHigh;
    private View savedBar, optionsRow;
    private WaveformView wave;
    private ListView list;
    private final List<File> files = new ArrayList<>();
    private final Map<String, Long> durationCache = new HashMap<>();
    private RecAdapter adapter;
    private SharedPreferences prefs;
    private TextView settingsFolderName, settingsTxFolderName; // live labels inside settings
    private TextView liveTv;          // open transcript page, updated live
    private String liveBase;
    private android.widget.ScrollView liveScroll;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ticks++;
            boolean blinkOn = (ticks / 8) % 2 == 0; // about every half second
            if (svc != null && svc.isRecording()) {
                if (!svc.isPaused()) {
                    wave.add((float) Math.sqrt(svc.level()));
                    txtState.setText(blinkOn ? "\u25CF  REC" : "     REC");
                    txtTimer.setAlpha(1f);
                } else {
                    txtTimer.setAlpha(blinkOn ? 1f : 0.35f);
                }
                txtTimer.setText(fmt(svc.elapsed()));
                if (ticks % 80 == 0 && freeBytes() < LOW_SPACE) {
                    toast("Storage almost full. Recording stopped and saved.");
                    sendToService(RecorderService.ACTION_STOP);
                }
            } else {
                txtTimer.setAlpha(1f);
            }
            updatePlayback();
            ui.postDelayed(this, 60);
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((RecorderService.LocalBinder) b).get();
            svc.setListener(MainActivity.this);
            File saved = svc.takeLastSaved();
            if (saved != null) showSaved(saved);
            renderRecordingState();
            refreshList();
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = Storage.prefs(this);
        File oldModel = new File(getFilesDir(), "vosk-en"); // model folder from the previous version
        if (oldModel.exists() && !LocalTranscriber.isReady(this, "small"))
            oldModel.renameTo(LocalTranscriber.modelDir(this, "small"));
        quality = prefs.getInt("quality", 1);
        useMp3 = prefs.getBoolean("mp3", true);

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
        privateNext = prefs.getBoolean("privateNext", false);
        chipTx.setOnClickListener(v -> settingsDialog());
        chipPrivate.setOnClickListener(v -> {
            if (svc != null && svc.isRecording()) return;
            privateNext = !privateNext;
            prefs.edit().putBoolean("privateNext", privateNext).apply();
            renderTxChip();
            if (privateNext) toast(LocalTranscriber.isModelReady(this)
                    ? "Private: transcribed on this phone only"
                    : "Private: nothing leaves the phone. Download the model in Settings for transcripts.");
        });
        txtSort.setOnClickListener(this::sortMenu);
        transcriptBox.setOnClickListener(v -> {
            String b0 = currentBase();
            if (b0 != null) openTranscript(b0);
        });
        renderTxChip();
        sortMode = prefs.getInt("sort", 0);
        btnSort.setOnClickListener(this::sortMenu);
        findViewById(R.id.btnNotifOn).setOnClickListener(v -> openNotificationSettings());
        txtTimer = findViewById(R.id.txtTimer);
        txtState = findViewById(R.id.txtState);
        txtFolder = findViewById(R.id.txtFolder);
        txtListHeader = findViewById(R.id.txtListHeader);
        chipMp3 = findViewById(R.id.chipMp3);
        chipM4a = findViewById(R.id.chipM4a);
        chipVoice = findViewById(R.id.chipVoice);
        chipHigh = findViewById(R.id.chipHigh);
        savedBar = findViewById(R.id.savedBar);
        optionsRow = findViewById(R.id.optionsRow);
        wave = findViewById(R.id.wave);
        list = findViewById(R.id.list);
        list.setEmptyView(findViewById(R.id.empty));
        adapter = new RecAdapter();
        list.setAdapter(adapter);

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
        btnSettings.setOnClickListener(v -> settingsDialog());
        txtFolder.setOnClickListener(v -> settingsDialog());

        chipMp3.setOnClickListener(v -> { useMp3 = true; savePrefs(); });
        chipM4a.setOnClickListener(v -> { useMp3 = false; savePrefs(); });
        chipVoice.setOnClickListener(v -> { quality = 0; savePrefs(); });
        chipHigh.setOnClickListener(v -> { quality = 1; savePrefs(); });
        findViewById(R.id.btnSavedShare).setOnClickListener(v -> { if (lastSavedFile != null) shareMenu(lastSavedFile); });
        findViewById(R.id.btnSavedWa).setOnClickListener(v -> { if (lastSavedFile != null) shareWhatsAppDocument(lastSavedFile); });
        findViewById(R.id.btnSavedRename).setOnClickListener(v -> { if (lastSavedFile != null) rename(lastSavedFile); });

        renderChips();
        txtFolder.setText(Storage.folderLabel(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, RecorderService.class), conn, Context.BIND_AUTO_CREATE);
        refreshList();
        updateNotifBar();
        Transcriber.get(this).setListener(this);
        checkPending();
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tick);
        if (svc != null) svc.setListener(null);
        Transcriber.get(this).setListener(null);
        unbindService(conn);
        svc = null;
        stopPlaying(); // recording keeps running in the service
    }

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
            need.add(Manifest.permission.POST_NOTIFICATIONS); // Android stops asking after 2 refusals
        }
        if (need.isEmpty()) startRecording();
        else ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (rc != REQ_PERMS) return;
        updateNotifBar();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startRecording();
        else toast("Microphone permission is needed to record");
    }

    // ---------- Recording (via service) ----------
    private void startRecording() {
        if (freeBytes() < LOW_SPACE * 2) { toast("Not enough storage to record. Free up some space."); return; }
        stopPlaying();
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
        findViewById(R.id.optionsRow2).setAlpha(rec ? 0.4f : 1f);
        wave.setActive(rec && !paused);
        wave.setVisibility(rec || savedBar.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        if (rec) {
            savedBar.setVisibility(View.GONE);
            txtState.setText(paused ? "PAUSED" : "\u25CF  REC");
            txtFree.setText(svc.currentFile().getName());
            txtState.setTextColor(ContextCompat.getColor(this,
                    paused ? R.color.muted : R.color.rec_red));
            txtTimer.setText(fmt(svc.elapsed()));
        } else {
            txtState.setTextColor(ContextCompat.getColor(this, R.color.muted));
            if (savedBar.getVisibility() != View.VISIBLE) {
                txtState.setText("Ready to record");
                txtTimer.setText("00:00");
            }
            txtFree.setText(freeText());
        }
    }

    @Override public void onStateChanged() { renderRecordingState(); }

    @Override public void onError(String message) {
        toast(message);
        renderRecordingState();
    }

    @Override public void onSaved(File file, boolean copied) {
        showSaved(file);
        if (Storage.folderUri(this) != null && !copied)
            toast("Saved in app, but could not copy to your folder. Choose it again in Settings.");
    }

    private void showSaved(File f) {
        lastSavedFile = f;
        txtState.setTextColor(ContextCompat.getColor(this, R.color.muted));
        txtState.setText(f.getName());
        txtTimer.setText(fmt(durationOf(f)));
        savedBar.setVisibility(View.VISIBLE);
        wave.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideSaved);
        ui.postDelayed(hideSaved, 20000);
        refreshList();
    }

    private static final String[] SORT_NAMES = {"Newest", "Oldest", "Name", "Longest"};

    private void sortMenu(View anchor) {
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
    private boolean notificationsAllowed() {
        return androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    private void updateNotifBar() {
        notifBar.setVisibility(notificationsAllowed() ? View.GONE : View.VISIBLE);
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
        if (privateNext) label = LocalTranscriber.isModelReady(this) ? "On device  ·  " + every : "Private: no transcript";
        else if ("groq".equals(mode)) label = "Groq  ·  " + every;
        else if ("local".equals(mode)) label = "On device  ·  " + every;
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
        String fileText = readText(all);
        // Groq sends one part at a time (file has everything); on-device sends the full text so far
        String full = fileText.length() >= text.length() ? fileText : text;
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
        // "Don't send" lives as a link-style row so the main choices stay clear
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

    private boolean isPrivate(File f) {
        return prefs.getBoolean("private:" + Transcriber.stem(f.getName()), false);
    }

    private void transcribeWithGroq(File f) {
        String key = prefs.getString("groqKey", "");
        if (key == null || key.trim().isEmpty()) { toast("Add your Groq API key in Settings first"); settingsDialog(); return; }
        if (!Transcriber.get(this).isOnline()) { toast("No internet. Try again when online."); return; }
        new File(Storage.recDir(this), Transcriber.stem(f.getName()) + ".txt").delete();
        if (!Transcriber.get(this).queueWholeFile(f)) toast("File is larger than 25 MB, too big for Groq free tier");
        else { lastSavedFile = f; toast("Sending to Groq..."); }
    }

    private void transcribeOnDevice(File f) {
        if (!LocalTranscriber.isModelReady(this)) { toast("Download the on-device model in Settings first"); settingsDialog(); return; }
        lastSavedFile = f;
        LocalTranscriber.transcribeFile(this, f);
        toast("Transcribing on this phone...");
    }

    private void viewTranscript(File txt) {
        openTranscript(Transcriber.stem(txt.getName()));
    }

    /** Full-screen, scrollable transcript. Updates live while that recording is in progress. */
    private void openTranscript(String base) {
        File txt = new File(Storage.recDir(this), base + ".txt");
        String text = readText(txt);
        if (text.isEmpty() && base.equals(currentBase()) && txtTranscript.getText().length() > 0
                && txtTranscript.getCurrentTextColor() != ContextCompat.getColor(this, R.color.muted))
            text = txtTranscript.getText().toString();
        boolean recordingThis = svc != null && svc.isRecording() && base.equals(currentBase());

        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text));
        tv.setTextSize(17);
        tv.setLineSpacing(0, 1.35f);
        tv.setText(text.isEmpty() ? (recordingThis ? "Listening..." : "No speech was found in this recording.") : text);
        int pad = dp(16);
        tv.setPadding(pad, pad, pad, pad);
        tv.setBackgroundResource(R.drawable.bg_card);

        NaadDialog d = new NaadDialog(this, NaadDialog.FULL).title(recordingThis ? "Live transcript" : "Transcript");
        TextView sub = new TextView(this);
        sub.setText(displayNameOf(base) + (recordingThis ? "  \u00b7  updating as you speak" : ""));
        sub.setTextColor(ContextCompat.getColor(this, R.color.muted));
        sub.setTextSize(13);
        sub.setPadding(dp(4), 0, 0, dp(12));
        d.view(sub, false).view(tv, false);
        d.button("Copy", NaadDialog.SECONDARY, true, () -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", tv.getText()));
            toast("Copied");
        });
        d.button("Share", NaadDialog.PRIMARY, true, () -> shareText(tv.getText().toString()));
        liveBase = base;
        liveTv = tv;
        d.onDismiss(() -> { liveTv = null; liveBase = null; liveScroll = null; });
        d.show();
        View p = tv.getParent() instanceof View ? (View) tv.getParent() : null;
        while (p != null && !(p instanceof android.widget.ScrollView))
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        liveScroll = (android.widget.ScrollView) p;
        if (recordingThis && liveScroll != null) liveScroll.post(() -> liveScroll.fullScroll(View.FOCUS_DOWN));
    }

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
        NaadDialog d = new NaadDialog(this, NaadDialog.SHEET).title("Share").subtitle(displayName(f));
        d.item("Audio file", R.drawable.ic_share, false, () -> share(f));
        if (hasTxt) {
            d.item("Transcript as text", R.drawable.ic_doc, false, () -> shareText(readText(txt)));
            d.item("Transcript file (.txt)", R.drawable.ic_doc, false,
                    () -> shareFiles(java.util.Collections.singletonList(txt), "text/plain"));
            d.item("Audio and transcript", R.drawable.ic_share, false,
                    () -> shareFiles(Arrays.asList(f, txt), "*/*"));
            List<File> parts = partsOf(f);
            if (parts.size() > 1)
                d.item("All " + parts.size() + " transcript parts", R.drawable.ic_doc, false,
                        () -> shareFiles(parts, "text/plain"));
        }
        d.show();
    }
    private static String readText(File f) {
        if (!f.exists()) return "";
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private File transcriptOf(File f) {
        return new File(Storage.recDir(this), Transcriber.stem(f.getName()) + ".txt");
    }

    // ---------- Settings ----------
    private void settingsDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        EditText ed = v.findViewById(R.id.edPrefix);
        TextView preview = v.findViewById(R.id.txtPreview);
        settingsFolderName = v.findViewById(R.id.txtFolderName);
        settingsTxFolderName = v.findViewById(R.id.txtTxFolderName);

        ed.setText(Storage.prefix(this));
        ed.setSelection(ed.getText().length());
        Runnable upd = () -> {
            String p = ed.getText().toString().trim();
            if (p.isEmpty()) p = "Recording";
            preview.setText("Example: " + Storage.cleanName(p) + "_"
                    + new SimpleDateFormat("dd-MM-yyyy_hh-mm-ssa", Locale.ENGLISH).format(new Date())
                    + (useMp3 ? ".mp3" : ".m4a"));
        };
        upd.run();
        ed.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { upd.run(); }
        });

        settingsFolderName.setText(Storage.folderLabel(this));
        settingsTxFolderName.setText(Storage.txFolderLabel(this));
        v.findViewById(R.id.btnChangeFolder).setOnClickListener(x -> pickFolder(REQ_FOLDER));
        v.findViewById(R.id.btnChangeTxFolder).setOnClickListener(x -> pickFolder(REQ_TXFOLDER));

        // transcription
        final String[] mode = {Storage.txMode(this)};
        final int[] mins = {Storage.txMinutes(this)};
        final String[] model = {LocalTranscriber.selected(this)};
        TextView txOff = v.findViewById(R.id.txOff), txLocal = v.findViewById(R.id.txLocal),
                txGroq = v.findViewById(R.id.txGroq), txHint = v.findViewById(R.id.txHint),
                txtModel = v.findViewById(R.id.txtModel), btnModel = v.findViewById(R.id.btnModel),
                mSmall = v.findViewById(R.id.mSmall), mLarge = v.findViewById(R.id.mLarge);
        View intervalSection = v.findViewById(R.id.intervalSection);
        View groqSection = v.findViewById(R.id.groqSection);
        View localSection = v.findViewById(R.id.localSection);
        int[] ids = {R.id.tx1, R.id.tx5, R.id.tx10, R.id.tx15, R.id.txFull};
        int[] vals = {1, 5, 10, 15, -1};
        EditText edKey = v.findViewById(R.id.edGroqKey);
        edKey.setText(prefs.getString("groqKey", ""));
        final boolean[] downloading = {false};

        Runnable modelUpd = () -> {
            mSmall.setBackgroundResource("small".equals(model[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            mLarge.setBackgroundResource("large".equals(model[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            if (downloading[0]) return;
            boolean ready = LocalTranscriber.isReady(this, model[0]);
            txtModel.setText(ready ? "Downloaded, works offline" : "Not downloaded (" + LocalTranscriber.sizeOf(model[0]) + ")");
            btnModel.setText(ready ? "Delete" : "Download");
            btnModel.setTextColor(ContextCompat.getColor(this, ready ? R.color.muted : R.color.saffron));
        };
        mSmall.setOnClickListener(x -> { if (!downloading[0]) { model[0] = "small"; modelUpd.run(); } });
        mLarge.setOnClickListener(x -> { if (!downloading[0]) { model[0] = "large"; modelUpd.run(); } });
        modelUpd.run();
        btnModel.setOnClickListener(x -> {
            if (downloading[0]) return;
            String id = model[0];
            if (LocalTranscriber.isReady(this, id)) {
                new NaadDialog(this, false).title("Delete this model?")
                        .message("It will need to be downloaded again to use it.")
                        .button("Keep", NaadDialog.SECONDARY, null)
                        .button("Delete", NaadDialog.DANGER, () -> {
                            LocalTranscriber.deleteModel(this, id);
                            modelUpd.run();
                            renderTxChip();
                        }).show();
                return;
            }
            if (!Transcriber.get(this).isOnline()) { toast("Connect to the internet to download"); return; }
            downloading[0] = true;
            btnModel.setText("");
            txtModel.setText("Starting download...");
            LocalTranscriber.downloadModel(this, id, new LocalTranscriber.Progress() {
                public void update(String text) { ui.post(() -> txtModel.setText(text)); }
                public void done(boolean ok, String error) {
                    ui.post(() -> {
                        downloading[0] = false;
                        modelUpd.run();
                        if (!ok) toast("Download failed: " + error);
                        renderTxChip();
                    });
                }
            });
        });

        Runnable txUpd = () -> {
            txOff.setBackgroundResource("off".equals(mode[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            txLocal.setBackgroundResource("local".equals(mode[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            txGroq.setBackgroundResource("groq".equals(mode[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            intervalSection.setVisibility("off".equals(mode[0]) ? View.GONE : View.VISIBLE);
            groqSection.setVisibility("groq".equals(mode[0]) ? View.VISIBLE : View.GONE);
            localSection.setVisibility("groq".equals(mode[0]) ? View.GONE : View.VISIBLE);
            txHint.setText("off".equals(mode[0])
                    ? "Recordings are not transcribed. Recordings made with Private on still use the on-device model."
                    : "local".equals(mode[0])
                    ? "Runs fully on this phone, also offline. Nothing is uploaded."
                    : "Most accurate. Audio is sent to Groq. If you were offline, Naad asks before sending.");
            for (int i = 0; i < ids.length; i++)
                v.findViewById(ids[i]).setBackgroundResource(
                        mins[0] == vals[i] ? R.drawable.bg_chip : R.drawable.bg_chip_off);
        };
        txOff.setOnClickListener(x -> { mode[0] = "off"; txUpd.run(); });
        txLocal.setOnClickListener(x -> { mode[0] = "local"; txUpd.run(); });
        txGroq.setOnClickListener(x -> { mode[0] = "groq"; txUpd.run(); });
        for (int i = 0; i < ids.length; i++) {
            final int val = vals[i];
            v.findViewById(ids[i]).setOnClickListener(x -> { mins[0] = val; txUpd.run(); });
        }
        txUpd.run();

        // Settings save when the page closes (back arrow, back gesture or Done)
        new NaadDialog(this, NaadDialog.FULL)
                .title("Settings")
                .view(v, false)
                .button("Done", NaadDialog.PRIMARY, null)
                .onDismiss(() -> {
                    String p = ed.getText().toString().trim();
                    prefs.edit().putString("prefix", p.isEmpty() ? "Recording" : p)
                            .putString("txMode", mode[0]).putInt("txMinutes", mins[0])
                            .putString("voskModel", model[0])
                            .putString("groqKey", edKey.getText().toString().trim()).apply();
                    if (!"off".equals(mode[0]) && !useMp3)
                        toast("Transcription works with MP3. Switch the format to MP3.");
                    else if ("groq".equals(mode[0]) && edKey.getText().toString().trim().isEmpty())
                        toast("Add your Groq API key to transcribe");
                    else if ("local".equals(mode[0]) && !LocalTranscriber.isModelReady(this))
                        toast("Download an on-device model to transcribe");
                    txtFolder.setText(Storage.folderLabel(this));
                    renderTxChip();
                    settingsFolderName = null;
                    settingsTxFolderName = null;
                })
                .show();
    }

    private void pickFolder(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, req);
    }

    private static void deleteDir(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteDir(c);
        f.delete();
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc == REQ_FOLDER && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString("folder", tree.toString()).apply();
            txtFolder.setText(Storage.folderLabel(this));
            if (settingsFolderName != null) settingsFolderName.setText(Storage.folderLabel(this));
        }
        if (rc == REQ_TXFOLDER && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString("txFolder", tree.toString()).apply();
            if (settingsTxFolderName != null) settingsTxFolderName.setText(Storage.txFolderLabel(this));
        }
    }

    private void copyToFolder(File f) {
        if (Storage.folderUri(this) == null) { settingsDialog(); return; }
        new Thread(() -> {
            boolean ok = Storage.copyToFolder(this, f);
            ui.post(() -> toast(ok ? "Saved to " + Storage.folderLabel(this)
                    : "Could not save. Choose the folder again in Settings."));
        }).start();
    }

    // ---------- List ----------
    private void refreshList() {
        files.clear();
        File[] arr = Storage.recDir(this).listFiles((d, n) -> n.endsWith(".m4a") || n.endsWith(".mp3"));
        if (arr != null) {
            switch (sortMode) {
                case 1: Arrays.sort(arr, (a, b) -> Long.compare(a.lastModified(), b.lastModified())); break;
                case 2: Arrays.sort(arr, (a, b) -> a.getName().compareToIgnoreCase(b.getName())); break;
                case 3: Arrays.sort(arr, (a, b) -> Long.compare(durationOf(b), durationOf(a))); break;
                default: Arrays.sort(arr, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            }
            for (File f : arr) {
                // hide the file that is still being recorded
                if (svc != null && svc.isRecording() && f.equals(svc.currentFile())) continue;
                files.add(f);
            }
        }
        txtListHeader.setText(files.isEmpty() ? "Recordings" : "Recordings  (" + files.size() + ")");
        txtSort.setText(SORT_NAMES[sortMode] + "  \u25BE");
        txtSort.setVisibility(files.size() > 1 ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private class RecAdapter extends BaseAdapter {
        @Override public int getCount() { return files.size(); }
        @Override public Object getItem(int i) { return files.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_recording, parent, false);
            File f = files.get(pos);
            v.setTag(f);
            ((TextView) v.findViewById(R.id.txtName)).setText(displayName(f));
            ((TextView) v.findViewById(R.id.txtBadge)).setText(f.getName().endsWith(".mp3") ? "MP3" : "M4A");
            String date = shortDate(f.lastModified());
            ((TextView) v.findViewById(R.id.txtMeta)).setText(
                    fmt(durationOf(f)) + "  ·  " + size(f.length()) + "  ·  " + date);
            TextView txt = v.findViewById(R.id.txtTxt);
            boolean hasTxt = transcriptOf(f).exists();
            txt.setVisibility(hasTxt ? View.VISIBLE : View.GONE);
            txt.setOnClickListener(hasTxt ? x -> viewTranscript(transcriptOf(f)) : null);
            v.findViewById(R.id.imgLock).setVisibility(isPrivate(f) ? View.VISIBLE : View.GONE);

            boolean isCurrent = f.equals(playingFile) && player != null;
            boolean playing = isCurrent && player.isPlaying();
            ImageButton play = v.findViewById(R.id.btnItemPlay);
            play.setImageResource(playing ? R.drawable.ic_pause_small : R.drawable.ic_play);
            play.setContentDescription(playing ? "Pause" : "Play");
            play.setOnClickListener(x -> togglePlay(f));
            v.findViewById(R.id.btnItemMore).setOnClickListener(x -> itemMenu(x, f));
            v.findViewById(R.id.row).setOnClickListener(x -> togglePlay(f));

            View playRow = v.findViewById(R.id.playRow);
            playRow.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            SeekBar sb = v.findViewById(R.id.seek);
            sb.setOnSeekBarChangeListener(null);
            if (isCurrent) {
                sb.setMax(Math.max(1, player.getDuration()));
                sb.setProgress(player.getCurrentPosition());
                ((TextView) v.findViewById(R.id.txtPos)).setText(fmt(player.getCurrentPosition()));
                v.findViewById(R.id.btnBack10).setOnClickListener(x -> skip(-10000));
                v.findViewById(R.id.btnFwd10).setOnClickListener(x -> skip(10000));
                sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar s, int p, boolean user) {
                        if (user && player != null) player.seekTo(p);
                    }
                    public void onStartTrackingTouch(SeekBar s) { seeking = true; }
                    public void onStopTrackingTouch(SeekBar s) { seeking = false; }
                });
            }
            return v;
        }
    }

    /** Updates the seek bar of the visible playing card without rebuilding the list. */
    private void updatePlayback() {
        if (player == null || playingFile == null || seeking) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (playingFile.equals(c.getTag())) {
                SeekBar sb = c.findViewById(R.id.seek);
                int pos = player.getCurrentPosition();
                sb.setProgress(pos);
                ((TextView) c.findViewById(R.id.txtPos)).setText(fmt(pos));
            }
        }
    }

    private void itemMenu(View anchor, File f) {
        NaadDialog d = new NaadDialog(this, NaadDialog.SHEET).title(displayName(f))
                .subtitle(fmt(durationOf(f)) + "  \u00b7  " + size(f.length()) + "  \u00b7  "
                        + new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(new Date(f.lastModified()))
                        + (isPrivate(f) ? "  \u00b7  Private" : ""));
        d.item("Share", R.drawable.ic_share, false, () -> shareMenu(f));
        d.item("Send on WhatsApp as document", R.drawable.ic_chat, false, () -> shareWhatsAppDocument(f));
        d.divider();
        if (transcriptOf(f).exists()) d.item("View transcript", R.drawable.ic_doc, false, () -> viewTranscript(transcriptOf(f)));
        else {
            if (LocalTranscriber.isModelReady(this))
                d.item("Transcribe on this phone", R.drawable.ic_bolt, false, () -> transcribeOnDevice(f));
            if (!isPrivate(f)) d.item("Transcribe with Groq", R.drawable.ic_bolt, false, () -> transcribeWithGroq(f));
        }
        d.item("Rename", R.drawable.ic_edit, false, () -> rename(f));
        d.item("Save copy to " + Storage.folderLabel(this), R.drawable.ic_folder_small, false, () -> copyToFolder(f));
        d.divider();
        d.item("Delete", R.drawable.ic_delete, true, () -> delete(f));
        d.show();
    }
    private String displayNameOf(String base) { return displayName(new File(base + ".mp3")); }

    /** Default names like "Recording_23-11-2026_08-51-21AM" show as "Recording". */
    private String displayName(File f) {
        String n = stripExt(f.getName());
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*)_\\d{2}-\\d{2}-\\d{4}_\\d{2}-\\d{2}-\\d{2}(AM|PM|am|pm)$").matcher(n);
        if (m.matches() && !m.group(1).isEmpty()) return m.group(1);
        m = java.util.regex.Pattern.compile("^(REC|Dhwani)_\\d{8}_\\d{6}$").matcher(n);
        if (m.matches()) return m.group(1);
        return n;
    }

    private void togglePlay(File f) {
        if (svc != null && svc.isRecording()) { toast("Stop recording to play"); return; }
        if (f.equals(playingFile) && player != null) {
            if (player.isPlaying()) player.pause(); else player.start();
            adapter.notifyDataSetChanged();
            return;
        }
        stopPlaying();
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.start();
            playingFile = f;
            player.setOnCompletionListener(mp -> { stopPlaying(); adapter.notifyDataSetChanged(); });
        } catch (Exception e) {
            stopPlaying();
            toast("Could not play this file");
        }
        adapter.notifyDataSetChanged();
    }

    private void stopPlaying() {
        if (player != null) { player.release(); player = null; }
        playingFile = null;
    }

    // ---------- Share ----------
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

    // ---------- Rename / delete ----------
    private void rename(File f) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        et.setText(stripExt(f.getName()));
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
                    if (f.equals(playingFile)) stopPlaying();
                    String oldBase = Transcriber.stem(f.getName());
                    if (!f.renameTo(to)) { toast("Could not rename"); return; }
                    File[] txts = Storage.recDir(this).listFiles((dd, nn) ->
                            nn.equals(oldBase + ".txt") || (nn.startsWith(oldBase + "_part") && nn.endsWith(".txt")));
                    if (txts != null) for (File t : txts)
                        t.renameTo(new File(t.getParentFile(), n + t.getName().substring(oldBase.length())));
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
                .message(displayName(f) + " and its transcript will be removed from the app. Copies in your chosen folder stay.")
                .button("Cancel", NaadDialog.SECONDARY, null)
                .button("Delete", NaadDialog.DANGER, () -> {
                    if (f.equals(playingFile)) stopPlaying();
                    f.delete();
                    String b0 = Transcriber.stem(f.getName());
                    File[] txts = Storage.recDir(this).listFiles((dd, nn) ->
                            nn.equals(b0 + ".txt") || (nn.startsWith(b0 + "_part") && nn.endsWith(".txt")));
                    if (txts != null) for (File t : txts) t.delete();
                    prefs.edit().remove("private:" + b0).apply();
                    if (f.equals(lastSavedFile)) {
                        savedBar.setVisibility(View.GONE);
                        transcriptBox.setVisibility(View.GONE);
                    }
                    refreshList();
                })
                .show();
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

    private static String fmt(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private static String size(long b) {
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", b / (1024f * 1024f));
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    /** "9:22 am" today, "Yesterday", or "23 Nov". */
    private static String shortDate(long t) {
        java.util.Calendar now = java.util.Calendar.getInstance(), c = java.util.Calendar.getInstance();
        c.setTimeInMillis(t);
        boolean sameYear = now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR);
        int dayDiff = now.get(java.util.Calendar.DAY_OF_YEAR) - c.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameYear && dayDiff == 0)
            return new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(t)).toLowerCase(Locale.ENGLISH);
        if (sameYear && dayDiff == 1) return "Yesterday";
        return new SimpleDateFormat(sameYear ? "d MMM" : "d MMM yy", Locale.ENGLISH).format(new Date(t));
    }

    private void skip(int ms) {
        if (player == null) return;
        int p = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + ms));
        player.seekTo(p);
        updatePlayback();
    }

    private void buzz(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private long freeBytes() {
        try { return new StatFs(Storage.recDir(this).getAbsolutePath()).getAvailableBytes(); }
        catch (Exception e) { return Long.MAX_VALUE; }
    }

    private String freeText() {
        long free = freeBytes();
        if (free == Long.MAX_VALUE) return "";
        long bytesPerHour = (quality == 1 ? 128 : 64) * 1000L / 8 * 3600;
        long hours = free / bytesPerHour;
        String space = free >= 1024L * 1024 * 1024
                ? String.format(Locale.US, "%.1f GB free", free / (1024f * 1024f * 1024f))
                : (free / (1024 * 1024)) + " MB free";
        String time = hours >= 1000 ? "1000+ hours" : hours + (hours == 1 ? " hour" : " hours");
        return space + "  ·  about " + time + " at " + (quality == 1 ? "High" : "Voice");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
