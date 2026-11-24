package com.example.voicerecorder;

import android.content.ClipData;
import android.content.ClipboardManager;
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

/** Full-screen Settings dialog supporting On-device Vosk, Groq, Vertex AI, and Live Google Docs. */
public final class SettingsSheet {

    public interface Host {
        boolean isUsingMp3();
        void onPickAudioFolder();
        void onPickTranscriptFolder();
        void onPickServiceAccountJson();
        void onSettingsClosed();
    }

    private final Context ctx;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView folderNameView, txFolderNameView, saStatusView, gdocsSaNoteView;
    private View btnResetFolder, btnResetTxFolder, btnClearSaView;

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

    public void refreshServiceAccountLabels() {
        String saJson = Storage.vertexServiceAccountJson(ctx);
        GoogleAuth.ServiceAccount sa = GoogleAuth.parseServiceAccount(saJson);
        if (saStatusView != null) {
            if (sa != null) {
                saStatusView.setText("Loaded: " + sa.clientEmail
                        + (sa.projectId.isEmpty() ? "" : " (" + sa.projectId + ")"));
            } else {
                saStatusView.setText("No Service Account JSON loaded");
            }
        }
        if (btnClearSaView != null) {
            btnClearSaView.setVisibility(sa != null ? View.VISIBLE : View.GONE);
        }
        if (gdocsSaNoteView != null) {
            if (sa != null) {
                gdocsSaNoteView.setText("Share your Google Doc as Editor with:\n" + sa.clientEmail);
            } else {
                gdocsSaNoteView.setText("Import your Google Cloud Service Account JSON to append directly via Docs API.");
            }
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
        saStatusView = v.findViewById(R.id.txtSaStatus);
        gdocsSaNoteView = v.findViewById(R.id.txtGdocsSaNote);
        btnClearSaView = v.findViewById(R.id.btnClearSa);

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
        refreshServiceAccountLabels();
        v.findViewById(R.id.btnChangeFolder).setOnClickListener(x -> host.onPickAudioFolder());
        v.findViewById(R.id.btnChangeTxFolder).setOnClickListener(x -> host.onPickTranscriptFolder());
        btnResetFolder.setOnClickListener(x -> { Storage.clearFolder(ctx); refreshFolderLabels(); });
        btnResetTxFolder.setOnClickListener(x -> { Storage.clearTxFolder(ctx); refreshFolderLabels(); });

        // State arrays
        final String[] mode = {Storage.txMode(ctx)};
        final int[] mins = {Storage.txMinutes(ctx)};
        final String[] voskModel = {LocalTranscriber.isDownloading()
                ? LocalTranscriber.downloadingId() : LocalTranscriber.selected(ctx)};
        final String[] groqModel = {Storage.groqModel(ctx)};
        final String[] sharedLang = {Storage.groqLanguage(ctx)};
        final String[] vertexModel = {Storage.vertexModel(ctx)};
        final String[] vertexAuth = {Storage.vertexAuthMode(ctx)};
        final String[] vertexLoc = {Storage.vertexLocation(ctx)};
        final String[] gdocsMode = {Storage.gdocsMode(ctx)};

        // Transcription mode & interval views
        TextView txOff = v.findViewById(R.id.txOff);
        TextView txLocal = v.findViewById(R.id.txLocal);
        TextView txGroq = v.findViewById(R.id.txGroq);
        TextView txVertex = v.findViewById(R.id.txVertex);
        TextView txHint = v.findViewById(R.id.txHint);
        View intervalSection = v.findViewById(R.id.intervalSection);
        View localSection = v.findViewById(R.id.localSection);
        View groqSection = v.findViewById(R.id.groqSection);
        View vertexSection = v.findViewById(R.id.vertexSection);
        int[] intIds = {R.id.tx30s, R.id.tx1, R.id.tx5, R.id.tx10, R.id.tx15, R.id.txFull};
        int[] intVals = {0, 1, 5, 10, 15, -1};

        // Groq views
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
        wirePasswordToggle(edKey, btnToggleKey);
        btnTestKey.setOnClickListener(x -> {
            if (!Transcriber.get(ctx).isOnline()) { txtKeyStatus.setText("No internet connection."); return; }
            btnTestKey.setEnabled(false);
            txtKeyStatus.setText("Testing key...");
            Transcriber.get(ctx).testApiKey(edKey.getText().toString(), (ok, msg) -> {
                btnTestKey.setEnabled(true);
                txtKeyStatus.setText(msg);
                txtKeyStatus.setTextColor(ContextCompat.getColor(ctx, ok ? R.color.saffron : R.color.rec_red));
            });
        });

        // Vertex AI views
        TextView vFlash = v.findViewById(R.id.vFlash);
        TextView vPro = v.findViewById(R.id.vPro);
        TextView vLangAuto = v.findViewById(R.id.vLangAuto);
        TextView vLangEn = v.findViewById(R.id.vLangEn);
        TextView vLangHi = v.findViewById(R.id.vLangHi);
        TextView vAuthKey = v.findViewById(R.id.vAuthKey);
        TextView vAuthSa = v.findViewById(R.id.vAuthSa);
        View vKeyPanel = v.findViewById(R.id.vKeyPanel);
        View vSaPanel = v.findViewById(R.id.vSaPanel);
        EditText edVertexKey = v.findViewById(R.id.edVertexKey);
        TextView btnToggleVertexKey = v.findViewById(R.id.btnToggleVertexKey);
        TextView btnTestVertex = v.findViewById(R.id.btnTestVertex);
        TextView vLocGlobal = v.findViewById(R.id.vLocGlobal);
        TextView vLocUs = v.findViewById(R.id.vLocUs);
        TextView vLocAsia = v.findViewById(R.id.vLocAsia);
        TextView btnImportSa = v.findViewById(R.id.btnImportSa);
        TextView btnPasteSa = v.findViewById(R.id.btnPasteSa);
        TextView btnTestVertexSa = v.findViewById(R.id.btnTestVertexSa);
        TextView txtVertexStatus = v.findViewById(R.id.txtVertexStatus);

        edVertexKey.setText(Storage.vertexApiKey(ctx));
        wirePasswordToggle(edVertexKey, btnToggleVertexKey);

        Runnable langAndCloudUpd = () -> {
            gTurbo.setBackgroundResource(Storage.MODEL_TURBO.equals(groqModel[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            gLarge.setBackgroundResource(Storage.MODEL_LARGE.equals(groqModel[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            gLangAuto.setBackgroundResource(sharedLang[0].isEmpty() ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            gLangEn.setBackgroundResource("en".equals(sharedLang[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            gLangHi.setBackgroundResource("hi".equals(sharedLang[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);

            vFlash.setBackgroundResource(Storage.VERTEX_MODEL_FLASH.equals(vertexModel[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            vPro.setBackgroundResource(Storage.VERTEX_MODEL_PRO.equals(vertexModel[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            vLangAuto.setBackgroundResource(sharedLang[0].isEmpty() ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            vLangEn.setBackgroundResource("en".equals(sharedLang[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            vLangHi.setBackgroundResource("hi".equals(sharedLang[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);

            boolean isSa = Storage.VERTEX_AUTH_SA.equals(vertexAuth[0]);
            vAuthKey.setBackgroundResource(!isSa ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            vAuthSa.setBackgroundResource(isSa ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            vKeyPanel.setVisibility(isSa ? View.GONE : View.VISIBLE);
            vSaPanel.setVisibility(isSa ? View.VISIBLE : View.GONE);

            vLocGlobal.setBackgroundResource("global".equals(vertexLoc[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            vLocUs.setBackgroundResource("us-central1".equals(vertexLoc[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            vLocAsia.setBackgroundResource("asia-south1".equals(vertexLoc[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
        };

        gTurbo.setOnClickListener(x -> { groqModel[0] = Storage.MODEL_TURBO; langAndCloudUpd.run(); });
        gLarge.setOnClickListener(x -> { groqModel[0] = Storage.MODEL_LARGE; langAndCloudUpd.run(); });
        gLangAuto.setOnClickListener(x -> { sharedLang[0] = ""; langAndCloudUpd.run(); });
        gLangEn.setOnClickListener(x -> { sharedLang[0] = "en"; langAndCloudUpd.run(); });
        gLangHi.setOnClickListener(x -> { sharedLang[0] = "hi"; langAndCloudUpd.run(); });

        vFlash.setOnClickListener(x -> { vertexModel[0] = Storage.VERTEX_MODEL_FLASH; langAndCloudUpd.run(); });
        vPro.setOnClickListener(x -> { vertexModel[0] = Storage.VERTEX_MODEL_PRO; langAndCloudUpd.run(); });
        vLangAuto.setOnClickListener(x -> { sharedLang[0] = ""; langAndCloudUpd.run(); });
        vLangEn.setOnClickListener(x -> { sharedLang[0] = "en"; langAndCloudUpd.run(); });
        vLangHi.setOnClickListener(x -> { sharedLang[0] = "hi"; langAndCloudUpd.run(); });
        vAuthKey.setOnClickListener(x -> { vertexAuth[0] = Storage.VERTEX_AUTH_KEY; langAndCloudUpd.run(); });
        vAuthSa.setOnClickListener(x -> { vertexAuth[0] = Storage.VERTEX_AUTH_SA; langAndCloudUpd.run(); });
        vLocGlobal.setOnClickListener(x -> { vertexLoc[0] = "global"; langAndCloudUpd.run(); });
        vLocUs.setOnClickListener(x -> { vertexLoc[0] = "us-central1"; langAndCloudUpd.run(); });
        vLocAsia.setOnClickListener(x -> { vertexLoc[0] = "asia-south1"; langAndCloudUpd.run(); });
        langAndCloudUpd.run();

        btnImportSa.setOnClickListener(x -> host.onPickServiceAccountJson());
        btnPasteSa.setOnClickListener(x -> showPasteSaDialog());
        btnClearSaView.setOnClickListener(x -> {
            Storage.setVertexServiceAccountJson(ctx, "");
            refreshServiceAccountLabels();
            toast("Service Account removed");
        });

        View.OnClickListener testVertexClick = x -> {
            if (!Transcriber.get(ctx).isOnline()) { txtVertexStatus.setText("No internet connection."); return; }
            x.setEnabled(false);
            txtVertexStatus.setText("Testing Vertex AI...");
            txtVertexStatus.setTextColor(ContextCompat.getColor(ctx, R.color.muted));
            VertexTranscriber.testConnection(
                    vertexAuth[0], edVertexKey.getText().toString(),
                    Storage.vertexServiceAccountJson(ctx), vertexLoc[0], vertexModel[0],
                    (ok, msg) -> {
                        x.setEnabled(true);
                        txtVertexStatus.setText(msg);
                        txtVertexStatus.setTextColor(ContextCompat.getColor(ctx, ok ? R.color.saffron : R.color.rec_red));
                    });
        };
        btnTestVertex.setOnClickListener(testVertexClick);
        btnTestVertexSa.setOnClickListener(testVertexClick);

        // On-device Vosk model views
        TextView txtModel = v.findViewById(R.id.txtModel);
        TextView btnModel = v.findViewById(R.id.btnModel);
        TextView btnCancelModel = v.findViewById(R.id.btnCancelModel);
        TextView mSmall = v.findViewById(R.id.mSmall);
        TextView mLarge = v.findViewById(R.id.mLarge);

        Runnable modelUpd = () -> {
            mSmall.setBackgroundResource("small".equals(voskModel[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            mLarge.setBackgroundResource("large".equals(voskModel[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            if (LocalTranscriber.isDownloading()) {
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
                    if (!ok && error != null) toast(error);
                });
            }
        };
        if (LocalTranscriber.isDownloading()) LocalTranscriber.setDownloadListener(dlProgress);

        mSmall.setOnClickListener(x -> { if (!LocalTranscriber.isDownloading()) { voskModel[0] = "small"; modelUpd.run(); } });
        mLarge.setOnClickListener(x -> { if (!LocalTranscriber.isDownloading()) { voskModel[0] = "large"; modelUpd.run(); } });
        btnCancelModel.setOnClickListener(x -> { LocalTranscriber.cancelDownload(); txtModel.setText("Cancelling..."); });
        modelUpd.run();

        btnModel.setOnClickListener(x -> {
            if (LocalTranscriber.isDownloading()) return;
            String id = voskModel[0];
            if (LocalTranscriber.isReady(ctx, id)) {
                new NaadDialog(ctx, false).title("Delete this model?")
                        .message("It will need to be downloaded again to use it.")
                        .button("Keep", NaadDialog.SECONDARY, null)
                        .button("Delete", NaadDialog.DANGER, () -> {
                            if (!LocalTranscriber.deleteModel(ctx, id)) {
                                toast("Model is currently in use. Try again after transcription finishes.");
                            }
                            modelUpd.run();
                        }).show();
                return;
            }
            if (!Transcriber.get(ctx).isOnline()) { toast("Connect to the internet to download"); return; }
            btnModel.setVisibility(View.GONE);
            btnCancelModel.setVisibility(View.VISIBLE);
            txtModel.setText("Starting download...");
            LocalTranscriber.downloadModel(ctx, id, dlProgress);
        });

        Runnable txUpd = () -> {
            txOff.setBackgroundResource("off".equals(mode[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            txLocal.setBackgroundResource("local".equals(mode[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            txGroq.setBackgroundResource("groq".equals(mode[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            txVertex.setBackgroundResource("vertex".equals(mode[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);

            intervalSection.setVisibility("off".equals(mode[0]) ? View.GONE : View.VISIBLE);
            localSection.setVisibility(("off".equals(mode[0]) || "local".equals(mode[0])) ? View.VISIBLE : View.GONE);
            groqSection.setVisibility("groq".equals(mode[0]) ? View.VISIBLE : View.GONE);
            vertexSection.setVisibility("vertex".equals(mode[0]) ? View.VISIBLE : View.GONE);

            if ("off".equals(mode[0])) {
                txHint.setText("Recordings are not transcribed. Private recordings still use the on-device model.");
            } else if ("local".equals(mode[0])) {
                txHint.setText("Runs fully on this phone (MP3 & M4A), also offline. Nothing is uploaded.");
            } else if ("groq".equals(mode[0])) {
                txHint.setText("Fast Whisper via Groq (MP3 & M4A). If offline, Naad asks before sending.");
            } else {
                txHint.setText("Gemini audio via Google Vertex AI (MP3 & M4A). Great for punctuation & multilingual speech.");
            }
            for (int i = 0; i < intIds.length; i++) {
                v.findViewById(intIds[i]).setBackgroundResource(
                        mins[0] == intVals[i] ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            }
        };
        txOff.setOnClickListener(x -> { mode[0] = "off"; txUpd.run(); });
        txLocal.setOnClickListener(x -> { mode[0] = "local"; txUpd.run(); });
        txGroq.setOnClickListener(x -> { mode[0] = "groq"; txUpd.run(); });
        txVertex.setOnClickListener(x -> { mode[0] = "vertex"; txUpd.run(); });
        for (int i = 0; i < intIds.length; i++) {
            final int val = intVals[i];
            v.findViewById(intIds[i]).setOnClickListener(x -> { mins[0] = val; txUpd.run(); });
        }
        txUpd.run();

        // Live Google Docs views
        TextView gdOff = v.findViewById(R.id.gdOff);
        TextView gdScript = v.findViewById(R.id.gdScript);
        TextView gdApi = v.findViewById(R.id.gdApi);
        TextView gdHint = v.findViewById(R.id.gdHint);
        View gdScriptPanel = v.findViewById(R.id.gdScriptPanel);
        View gdApiPanel = v.findViewById(R.id.gdApiPanel);
        EditText edGdocsScriptUrl = v.findViewById(R.id.edGdocsScriptUrl);
        EditText edGdocsSecret = v.findViewById(R.id.edGdocsSecret);
        EditText edGdocsDocUrl = v.findViewById(R.id.edGdocsDocUrl);
        TextView btnCopyScript = v.findViewById(R.id.btnCopyScript);
        TextView btnTestGdocsScript = v.findViewById(R.id.btnTestGdocsScript);
        TextView btnGdocsImportSa = v.findViewById(R.id.btnGdocsImportSa);
        TextView btnTestGdocsApi = v.findViewById(R.id.btnTestGdocsApi);
        TextView txtGdocsStatus = v.findViewById(R.id.txtGdocsStatus);

        edGdocsScriptUrl.setText(Storage.gdocsScriptUrl(ctx));
        edGdocsSecret.setText(Storage.gdocsScriptSecret(ctx));
        edGdocsDocUrl.setText(Storage.gdocsDocId(ctx));

        Runnable gdocsUpd = () -> {
            gdOff.setBackgroundResource(Storage.GDOCS_OFF.equals(gdocsMode[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            gdScript.setBackgroundResource(Storage.GDOCS_SCRIPT.equals(gdocsMode[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            gdApi.setBackgroundResource(Storage.GDOCS_API.equals(gdocsMode[0]) ? R.drawable.bg_chip : R.drawable.bg_chip_off);
            gdScriptPanel.setVisibility(Storage.GDOCS_SCRIPT.equals(gdocsMode[0]) ? View.VISIBLE : View.GONE);
            gdApiPanel.setVisibility(Storage.GDOCS_API.equals(gdocsMode[0]) ? View.VISIBLE : View.GONE);
            if (Storage.GDOCS_OFF.equals(gdocsMode[0])) {
                gdHint.setText("Stream live transcripts directly into a Google Doc as you record. Private recordings are never synced.");
            } else if (Storage.GDOCS_SCRIPT.equals(gdocsMode[0])) {
                gdHint.setText("Easiest setup: deploy our 30-line script at script.google.com. Automatically creates a live Google Doc per recording in your Drive.");
            } else {
                gdHint.setText("Appends live transcript directly via Google Docs REST API using your Google Cloud Service Account JSON.");
            }
        };
        gdOff.setOnClickListener(x -> { gdocsMode[0] = Storage.GDOCS_OFF; gdocsUpd.run(); });
        gdScript.setOnClickListener(x -> { gdocsMode[0] = Storage.GDOCS_SCRIPT; gdocsUpd.run(); });
        gdApi.setOnClickListener(x -> { gdocsMode[0] = Storage.GDOCS_API; gdocsUpd.run(); });
        gdocsUpd.run();

        btnCopyScript.setOnClickListener(x -> {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Naad Google Apps Script", GoogleDocsSync.APPS_SCRIPT_TEMPLATE));
                toast("Apps Script copied! Paste at script.google.com -> Deploy as Web App");
            }
        });
        btnGdocsImportSa.setOnClickListener(x -> host.onPickServiceAccountJson());

        View.OnClickListener testGdocsClick = x -> {
            if (!Transcriber.get(ctx).isOnline()) { txtGdocsStatus.setText("No internet connection."); return; }
            x.setEnabled(false);
            txtGdocsStatus.setText("Testing Google Docs...");
            txtGdocsStatus.setTextColor(ContextCompat.getColor(ctx, R.color.muted));
            GoogleDocsSync.get(ctx).testConnection(
                    gdocsMode[0],
                    edGdocsScriptUrl.getText().toString(),
                    edGdocsSecret.getText().toString(),
                    edGdocsDocUrl.getText().toString(),
                    Storage.vertexServiceAccountJson(ctx),
                    (ok, msg) -> {
                        x.setEnabled(true);
                        txtGdocsStatus.setText(msg);
                        txtGdocsStatus.setTextColor(ContextCompat.getColor(ctx, ok ? R.color.saffron : R.color.rec_red));
                    });
        };
        btnTestGdocsScript.setOnClickListener(testGdocsClick);
        btnTestGdocsApi.setOnClickListener(testGdocsClick);

        new NaadDialog(ctx, NaadDialog.FULL)
                .title("Settings")
                .view(v, false)
                .button("Done", NaadDialog.PRIMARY, null)
                .onDismiss(() -> {
                    LocalTranscriber.setDownloadListener(null);
                    String p = edPrefix.getText().toString().trim();
                    prefs.edit()
                            .putString("prefix", p.isEmpty() ? "Recording" : p)
                            .putString("txMode", mode[0])
                            .putInt("txMinutes", mins[0])
                            .putString("voskModel", voskModel[0])
                            .putString("groqModel", groqModel[0])
                            .putString("groqLang", sharedLang[0])
                            .putString("vertexModel", vertexModel[0])
                            .putString("vertexAuthMode", vertexAuth[0])
                            .putString("vertexLocation", vertexLoc[0])
                            .putString("gdocsMode", gdocsMode[0])
                            .apply();
                    Storage.setGroqKey(ctx, edKey.getText().toString());
                    Storage.setVertexApiKey(ctx, edVertexKey.getText().toString());
                    Storage.setGdocsScriptUrl(ctx, edGdocsScriptUrl.getText().toString());
                    Storage.setGdocsScriptSecret(ctx, edGdocsSecret.getText().toString());
                    Storage.setGdocsDocId(ctx, edGdocsDocUrl.getText().toString());

                    if ("groq".equals(mode[0]) && Storage.groqKey(ctx).isEmpty()) {
                        toast("Add your Groq API key to transcribe");
                    } else if ("vertex".equals(mode[0]) && !Storage.hasVertexCredentials(ctx)) {
                        toast("Add your Vertex AI key or Service Account JSON to transcribe");
                    } else if ("local".equals(mode[0]) && !LocalTranscriber.isModelReady(ctx)) {
                        toast("Download an on-device model to transcribe");
                    }
                    folderNameView = null;
                    txFolderNameView = null;
                    btnResetFolder = null;
                    btnResetTxFolder = null;
                    saStatusView = null;
                    gdocsSaNoteView = null;
                    btnClearSaView = null;
                    host.onSettingsClosed();
                })
                .show();
    }

    private void showPasteSaDialog() {
        EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(5);
        et.setMaxLines(10);
        et.setTextColor(ContextCompat.getColor(ctx, R.color.text));
        et.setHint("{\"type\":\"service_account\", ...}");
        new NaadDialog(ctx, false)
                .title("Paste Service Account JSON")
                .view(et, true)
                .button("Cancel", NaadDialog.SECONDARY, null)
                .button("Save", NaadDialog.PRIMARY, () -> {
                    String raw = et.getText().toString().trim();
                    if (GoogleAuth.parseServiceAccount(raw) == null) {
                        toast("Invalid Service Account JSON (needs client_email and private_key)");
                        return;
                    }
                    Storage.setVertexServiceAccountJson(ctx, raw);
                    refreshServiceAccountLabels();
                    toast("Service Account saved (encrypted)");
                })
                .show();
    }

    private static void wirePasswordToggle(EditText edit, TextView toggle) {
        final boolean[] visible = {false};
        toggle.setOnClickListener(x -> {
            visible[0] = !visible[0];
            int sel = edit.getSelectionEnd();
            edit.setInputType(visible[0]
                    ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            if (sel >= 0) edit.setSelection(sel);
            toggle.setText(visible[0] ? "Hide" : "Show");
        });
    }

    private void toast(String s) { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show(); }
}
