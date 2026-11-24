package com.example.voicerecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

/** Full-screen Settings dialog extracted from MainActivity. */
public final class SettingsSheet {

    public interface Host {
        boolean isUsingMp3();
        void onPickAudioFolder();
        void onPickTranscriptFolder();
        void onSettingsClosed();
    }

    private final Context ctx;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView folderNameView, txFolderNameView;
    private View btnResetFolder, btnResetTxFolder;

    public SettingsSheet(Context c, Host h) {
        this.ctx = c;
        this.host = h;
    }

    public void refreshFolderLabels() {
        if (folderNameView != null) folderNameView.setText(Storage.folderLabel(ctx));
        if (btnResetFolder != null) {
            btnResetFolder.setVisibility(Storage.folderUri(ctx) != null ? View.VISIBLE : View.GONE);
        }
        if (txFolderNameView != null) txFolderNameView.setText(Storage.txFolderLabel(ctx));
        if (btnResetTxFolder != null) {
            btnResetTxFolder.setVisibility(Storage.hasCustomTxFolder(ctx) ? View.VISIBLE : View.GONE);
        }
    }

    public void show() {
        SharedPreferences prefs = Storage.prefs(ctx);
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_settings, null);
        EditText edPrefix = v.findViewById(R.id.edPrefix);
        TextView preview = v.findViewById(R.id.txtPreview);
        folderNameView = v.findViewById(R.id.txtFolderName);
        txFolderNameView = v.findViewById(R.id.txtTxFolderName);
        btnResetFolder = v.findViewById(R.id.btnResetFolder);
        btnResetTxFolder = v.findViewById(R.id.btnResetTxFolder);

        edPrefix.setText(Storage.prefix(ctx));
        edPrefix.setSelection(edPrefix.getText().length());
        Runnable updPreview = () -> {
            String p = edPrefix.getText().toString().trim();
            if (p.isEmpty()) p = "Recording";
            String clean = Storage.cleanName(p);
            if (clean.isEmpty()) clean = "Recording";
            preview.setText("Example: " + clean + "_"
                    + Formatters.timestampStamp(System.currentTimeMillis())
                    + (host.isUsingMp3() ? ".mp3" : ".m4a"));
        };
        updPreview.run();
        edPrefix.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { updPreview.run(); }
        });

        refreshFolderLabels();
        v.findViewById(R.id.btnChangeFolder).setOnClickListener(x -> host.onPickAudioFolder());
        v.findViewById(R.id.btnChangeTxFolder).setOnClickListener(x -> host.onPickTranscriptFolder());
        btnResetFolder.setOnClickListener(x -> { Storage.clearFolder(ctx); refreshFolderLabels(); });
        btnResetTxFolder.setOnClickListener(x -> { Storage.clearTxFolder(ctx); refreshFolderLabels(); });

        // Transcription settings
        final String[] mode = {Storage.txMode(ctx)};
        final int[] mins = {Storage.txMinutes(ctx)};
        final String[] voskModel = {LocalTranscriber.isDownloading()
                ? LocalTranscriber.downloadingId() : LocalTranscriber.selected(ctx)};
        final String[] groqModel = {Storage.groqModel(ctx)};
        final String[] groqLang = {Storage.groqLanguage(ctx)};

        TextView txOff = v.findViewById(R.id.txOff);
        TextView txLocal = v.findViewById(R.id.txLocal);
        TextView txGroq = v.findViewById(R.id.txGroq);
        TextView txHint = v.findViewById(R.id.txHint);
        TextView txtModel = v.findViewById(R.id.txtModel);
        TextView btnModel = v.findViewById(R.id.btnModel);
        TextView btnCancelModel = v.findViewById(R.id.btnCancelModel);
        TextView mSmall = v.findViewById(R.id.mSmall);
        TextView mLarge = v.findViewById(R.id.mLarge);
        View intervalSection = v.findViewById(R.id.intervalSection);
        View groqSection = v.findViewById(R.id.groqSection);
        View localSection = v.findViewById(R.id.localSection);
        int[] intIds = {R.id.tx1, R.id.tx5, R.id.tx10, R.id.tx15, R.id.txFull};
        int[] intVals = {1, 5, 10, 15, -1};

        // Groq controls
        TextView gTurbo = v.findViewById(R.id.gTurbo);
        TextView gLarge = v.findViewById(R.id.gLarge);
        TextView gLangAuto = v.findViewById(R.id.gLangAuto);
        TextView gLangEn = v.findViewById(R.id.gLangEn);
        TextView gLangHi = v.findViewById(R.id.gLangHi);
        EditText edKey = v.findViewById(R.id.edGroqKey);
        TextView btnToggleKey = v.findViewById(R.id.btnToggleKey);
        TextView btnTestKey = v.findViewById(R.id.btnTestKey);
        TextView txtKeyStatus = v.findViewById(R.id.txtKeyStatus);

        edKey.setText(Storage.groqKey(ctx));
        final boolean[] keyVisible = {false};
        btnToggleKey.setOnClickListener(x -> {
            keyVisible[0] = !keyVisible[0];
            int sel = edKey.getSelectionEnd();
            edKey.setInputType(keyVisible[0]
                    ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            if (sel >= 0) edKey.setSelection(sel);
            btnToggleKey.setText(keyVisible[0] ? "Hide" : "Show");
        });
        btnTestKey.setOnClickListener(x -> {
            if (!Transcriber.get(ctx).isOnline()) {
                txtKeyStatus.setText("No internet connection.");
                return;
            }
            btnTestKey.setEnabled(false);
            txtKeyStatus.setText("Testing key...");
            Transcriber.get(ctx).testApiKey(edKey.getText().toString(), (ok, msg) -> {
                btnTestKey.setEnabled(true);
                txtKeyStatus.setText(msg);
                txtKeyStatus.setTextColor(ContextCompat.getColor(ctx, ok ? R.color.saffron : R.color.rec_red));
            });
        });

        Runnable groqUpd = () -> {
            gTurbo.setBackgroundResource(Storage.MODEL_TURBO.equals(groqModel[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            gLarge.setBackgroundResource(Storage.MODEL_LARGE.equals(groqModel[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            gLangAuto.setBackgroundResource(groqLang[0].isEmpty() ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            gLangEn.setBackgroundResource("en".equals(groqLang[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            gLangHi.setBackgroundResource("hi".equals(groqLang[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
        };
        gTurbo.setOnClickListener(x -> { groqModel[0] = Storage.MODEL_TURBO; groqUpd.run(); });
        gLarge.setOnClickListener(x -> { groqModel[0] = Storage.MODEL_LARGE; groqUpd.run(); });
        gLangAuto.setOnClickListener(x -> { groqLang[0] = ""; groqUpd.run(); });
        gLangEn.setOnClickListener(x -> { groqLang[0] = "en"; groqUpd.run(); });
        gLangHi.setOnClickListener(x -> { groqLang[0] = "hi"; groqUpd.run(); });
        groqUpd.run();

        // On-device Vosk model controls (reattaches seamlessly if download is already active)
        Runnable modelUpd = () -> {
            mSmall.setBackgroundResource("small".equals(voskModel[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            mLarge.setBackgroundResource("large".equals(voskModel[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            boolean dl = LocalTranscriber.isDownloading();
            if (dl) {
                String st = LocalTranscriber.downloadStatus();
                txtModel.setText(st != null ? st : "Downloading...");
                btnModel.setVisibility(View.GONE);
                btnCancelModel.setVisibility(View.VISIBLE);
                return;
            }
            btnModel.setVisibility(View.VISIBLE);
            btnCancelModel.setVisibility(View.GONE);
            boolean ready = LocalTranscriber.isReady(ctx, voskModel[0]);
            txtModel.setText(ready ? "Downloaded, works offline"
                    : "Not downloaded (" + LocalTranscriber.sizeOf(voskModel[0]) + ")");
            btnModel.setText(ready ? "Delete" : "Download");
            btnModel.setTextColor(ContextCompat.getColor(ctx, ready ? R.color.muted : R.color.saffron));
        };

        LocalTranscriber.Progress dlProgress = new LocalTranscriber.Progress() {
            @Override public void update(String text) {
                ui.post(() -> {
                    txtModel.setText(text);
                    btnModel.setVisibility(View.GONE);
                    btnCancelModel.setVisibility(View.VISIBLE);
                });
            }
            @Override public void done(boolean ok, String error) {
                ui.post(() -> {
                    modelUpd.run();
                    if (!ok && error != null) Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show();
                });
            }
        };
        if (LocalTranscriber.isDownloading()) {
            LocalTranscriber.setDownloadListener(dlProgress);
        }

        mSmall.setOnClickListener(x -> { if (!LocalTranscriber.isDownloading()) { voskModel[0] = "small"; modelUpd.run(); } });
        mLarge.setOnClickListener(x -> { if (!LocalTranscriber.isDownloading()) { voskModel[0] = "large"; modelUpd.run(); } });
        btnCancelModel.setOnClickListener(x -> {
            LocalTranscriber.cancelDownload();
            txtModel.setText("Cancelling...");
        });
        modelUpd.run();

        btnModel.setOnClickListener(x -> {
            if (LocalTranscriber.isDownloading()) return;
            String id = voskModel[0];
            if (LocalTranscriber.isReady(ctx, id)) {
                new NaadDialog(ctx, false).title("Delete this model?")
                        .message("It will need to be downloaded again to use it.")
                        .button("Keep", NaadDialog.SECONDARY, null)
                        .button("Delete", NaadDialog.DANGER, () -> {
                            boolean deleted = LocalTranscriber.deleteModel(ctx, id);
                            if (!deleted) {
                                Toast.makeText(ctx, "Model is currently in use. Try again after transcription finishes.",
                                        Toast.LENGTH_SHORT).show();
                            }
                            modelUpd.run();
                        }).show();
                return;
            }
            if (!Transcriber.get(ctx).isOnline()) {
                Toast.makeText(ctx, "Connect to the internet to download", Toast.LENGTH_SHORT).show();
                return;
            }
            btnModel.setVisibility(View.GONE);
            btnCancelModel.setVisibility(View.VISIBLE);
            txtModel.setText("Starting download...");
            LocalTranscriber.downloadModel(ctx, id, dlProgress);
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
                    ? "Runs fully on this phone (MP3 and M4A), also offline. Nothing is uploaded."
                    : "Most accurate (MP3 and M4A). Audio is sent to Groq. If you were offline, Naad asks before sending.");
            for (int i = 0; i < intIds.length; i++) {
                v.findViewById(intIds[i]).setBackgroundResource(
                        mins[0] == intVals[i] ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            }
        };
        txOff.setOnClickListener(x -> { mode[0] = "off"; txUpd.run(); });
        txLocal.setOnClickListener(x -> { mode[0] = "local"; txUpd.run(); });
        txGroq.setOnClickListener(x -> { mode[0] = "groq"; txUpd.run(); });
        for (int i = 0; i < intIds.length; i++) {
            final int val = intVals[i];
            v.findViewById(intIds[i]).setOnClickListener(x -> { mins[0] = val; txUpd.run(); });
        }
        txUpd.run();

        new NaadDialog(ctx, NaadDialog.FULL)
                .title("Settings")
                .view(v, false)
                .button("Done", NaadDialog.PRIMARY, null)
                .onDismiss(() -> {
                    // Detach view callbacks if download is still running in background
                    LocalTranscriber.setDownloadListener(null);
                    String p = edPrefix.getText().toString().trim();
                    prefs.edit()
                            .putString("prefix", p.isEmpty() ? "Recording" : p)
                            .putString("txMode", mode[0])
                            .putInt("txMinutes", mins[0])
                            .putString("voskModel", voskModel[0])
                            .putString("groqModel", groqModel[0])
                            .putString("groqLang", groqLang[0])
                            .apply();
                    Storage.setGroqKey(ctx, edKey.getText().toString());
                    if ("groq".equals(mode[0]) && Storage.groqKey(ctx).isEmpty()) {
                        Toast.makeText(ctx, "Add your Groq API key to transcribe", Toast.LENGTH_SHORT).show();
                    } else if ("local".equals(mode[0]) && !LocalTranscriber.isModelReady(ctx)) {
                        Toast.makeText(ctx, "Download an on-device model to transcribe", Toast.LENGTH_SHORT).show();
                    }
                    folderNameView = null;
                    txFolderNameView = null;
                    btnResetFolder = null;
                    btnResetTxFolder = null;
                    host.onSettingsClosed();
                })
                .show();
    }
}
