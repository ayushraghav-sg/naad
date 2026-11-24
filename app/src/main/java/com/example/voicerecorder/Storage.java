package com.example.voicerecorder;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Settings and file locations shared by the UI, transcription engines, and recording service. */
public final class Storage {
    public static final String MODEL_TURBO = "whisper-large-v3-turbo";
    public static final String MODEL_LARGE = "whisper-large-v3";

    public static final String VERTEX_MODEL_FLASH = "gemini-2.5-flash";
    public static final String VERTEX_MODEL_PRO = "gemini-2.5-pro";
    public static final String VERTEX_AUTH_KEY = "key";
    public static final String VERTEX_AUTH_SA = "sa";

    public static final String GDOCS_OFF = "off";
    public static final String GDOCS_SCRIPT = "script";
    public static final String GDOCS_API = "api";

    private Storage() { }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("dhwani", Context.MODE_PRIVATE);
    }

    public static File recDir(Context c) {
        File d = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (d == null) d = new File(c.getFilesDir(), "Music");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static long freeBytes(Context c) {
        try {
            return new StatFs(recDir(c).getAbsolutePath()).getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    public static String prefix(Context c) {
        String p = prefs(c).getString("prefix", "Recording");
        p = p == null ? "" : p.trim();
        return p.isEmpty() ? "Recording" : p;
    }

    public static String cleanName(String s) {
        return Formatters.cleanName(s);
    }

    /** e.g. Recording_22-11-2026_05-07-32PM.mp3 */
    public static String newName(Context c, boolean mp3) {
        String stamp = Formatters.timestampStamp(System.currentTimeMillis());
        String base = cleanName(prefix(c));
        if (base.isEmpty()) base = "Recording";
        return base + "_" + stamp + (mp3 ? ".mp3" : ".m4a");
    }

    /** Transcription mode: "off", "local", "groq", or "vertex". */
    public static String txMode(Context c) {
        return prefs(c).getString("txMode", "off");
    }

    /**
     * Transcript chunk interval preference:
     * 0 = 30 seconds (live fast), 1/5/10/15 = minutes, -1 = full recording. Default 5.
     */
    public static int txMinutes(Context c) {
        return prefs(c).getInt("txMinutes", 5);
    }

    /** Interval in seconds: 30 when txMinutes==0, m*60 when >0, or -1 for full recording. */
    public static int txIntervalSeconds(Context c) {
        int m = txMinutes(c);
        if (m == 0) return 30;
        return m > 0 ? m * 60 : -1;
    }

    public static String txIntervalLabel(Context c) {
        int m = txMinutes(c);
        if (m < 0) return "full";
        if (m == 0) return "every 30s";
        return "every " + m + " min";
    }

    /** Groq Whisper model: whisper-large-v3-turbo (default) or whisper-large-v3. */
    public static String groqModel(Context c) {
        String m = prefs(c).getString("groqModel", MODEL_TURBO);
        return MODEL_LARGE.equals(m) ? MODEL_LARGE : MODEL_TURBO;
    }

    /** Shared language ISO-639-1 code for Groq & Vertex AI, or "" for auto-detect (default). */
    public static String groqLanguage(Context c) {
        String lang = prefs(c).getString("groqLang", "");
        return lang == null ? "" : lang.trim();
    }

    // ---------- Encrypted Secrets Helpers ----------
    private static String getSecret(Context c, String key) {
        SharedPreferences sp = prefs(c);
        String raw = sp.getString(key, "");
        if (raw == null || raw.isEmpty()) return "";
        if (!KeyStoreHelper.isEncrypted(raw)) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                String enc = KeyStoreHelper.encrypt(trimmed);
                if (KeyStoreHelper.isEncrypted(enc)) {
                    sp.edit().putString(key, enc).apply();
                }
            }
            return trimmed;
        }
        return KeyStoreHelper.decrypt(raw).trim();
    }

    private static void setSecret(Context c, String key, String value) {
        String trimmed = value == null ? "" : value.trim();
        SharedPreferences.Editor ed = prefs(c).edit();
        if (trimmed.isEmpty()) ed.remove(key);
        else ed.putString(key, KeyStoreHelper.encrypt(trimmed));
        ed.apply();
    }

    public static String groqKey(Context c) { return getSecret(c, "groqKey"); }
    public static void setGroqKey(Context c, String key) { setSecret(c, "groqKey", key); }

    // ---------- Google Vertex AI Settings ----------
    public static String vertexAuthMode(Context c) {
        String m = prefs(c).getString("vertexAuthMode", VERTEX_AUTH_KEY);
        return VERTEX_AUTH_SA.equals(m) ? VERTEX_AUTH_SA : VERTEX_AUTH_KEY;
    }

    public static String vertexApiKey(Context c) { return getSecret(c, "vertexApiKey"); }
    public static void setVertexApiKey(Context c, String key) { setSecret(c, "vertexApiKey", key); }

    public static String vertexServiceAccountJson(Context c) { return getSecret(c, "vertexSaJson"); }
    public static void setVertexServiceAccountJson(Context c, String json) {
        setSecret(c, "vertexSaJson", json);
        GoogleAuth.clearCache();
    }

    public static String vertexLocation(Context c) {
        String loc = prefs(c).getString("vertexLocation", "global");
        return (loc == null || loc.trim().isEmpty()) ? "global" : loc.trim();
    }

    public static String vertexModel(Context c) {
        String m = prefs(c).getString("vertexModel", VERTEX_MODEL_FLASH);
        return VERTEX_MODEL_PRO.equals(m) ? VERTEX_MODEL_PRO : VERTEX_MODEL_FLASH;
    }

    public static boolean hasVertexCredentials(Context c) {
        if (VERTEX_AUTH_SA.equals(vertexAuthMode(c))) {
            return GoogleAuth.parseServiceAccount(vertexServiceAccountJson(c)) != null;
        }
        return !vertexApiKey(c).isEmpty();
    }

    // ---------- Live Google Docs Settings ----------
    public static String gdocsMode(Context c) {
        String m = prefs(c).getString("gdocsMode", GDOCS_OFF);
        if (GDOCS_SCRIPT.equals(m) || GDOCS_API.equals(m)) return m;
        return GDOCS_OFF;
    }

    public static boolean isGdocsEnabled(Context c) {
        String m = gdocsMode(c);
        if (GDOCS_SCRIPT.equals(m)) return !gdocsScriptUrl(c).isEmpty();
        if (GDOCS_API.equals(m)) {
            return !gdocsDocId(c).isEmpty()
                    && GoogleAuth.parseServiceAccount(vertexServiceAccountJson(c)) != null;
        }
        return false;
    }

    public static String gdocsScriptUrl(Context c) { return getSecret(c, "gdocsScriptUrl"); }
    public static void setGdocsScriptUrl(Context c, String url) { setSecret(c, "gdocsScriptUrl", url); }

    public static String gdocsScriptSecret(Context c) { return getSecret(c, "gdocsScriptSecret"); }
    public static void setGdocsScriptSecret(Context c, String secret) { setSecret(c, "gdocsScriptSecret", secret); }

    public static String gdocsDocId(Context c) {
        return GoogleAuth.extractDocumentId(prefs(c).getString("gdocsDocId", ""));
    }

    public static void setGdocsDocId(Context c, String urlOrId) {
        String id = GoogleAuth.extractDocumentId(urlOrId);
        SharedPreferences.Editor ed = prefs(c).edit();
        if (id.isEmpty()) ed.remove("gdocsDocId");
        else ed.putString("gdocsDocId", id);
        ed.apply();
    }

    public static String gdocIdFor(Context c, String base) {
        return prefs(c).getString("gdocId:" + base, "");
    }

    public static String gdocUrlFor(Context c, String base) {
        return prefs(c).getString("gdocUrl:" + base, "");
    }

    public static void setGdocFor(Context c, String base, String docId, String docUrl) {
        SharedPreferences.Editor ed = prefs(c).edit();
        if (docId != null && !docId.isEmpty()) ed.putString("gdocId:" + base, docId);
        if (docUrl != null && !docUrl.isEmpty()) ed.putString("gdocUrl:" + base, docUrl);
        ed.apply();
    }

    public static void renameGdoc(Context c, String oldBase, String newBase) {
        String id = gdocIdFor(c, oldBase);
        String url = gdocUrlFor(c, oldBase);
        if (id.isEmpty() && url.isEmpty()) return;
        SharedPreferences.Editor ed = prefs(c).edit()
                .remove("gdocId:" + oldBase)
                .remove("gdocUrl:" + oldBase);
        if (!id.isEmpty()) ed.putString("gdocId:" + newBase, id);
        if (!url.isEmpty()) ed.putString("gdocUrl:" + newBase, url);
        ed.apply();
    }

    public static void removeGdoc(Context c, String base) {
        prefs(c).edit().remove("gdocId:" + base).remove("gdocUrl:" + base).apply();
    }

    // ---------- SAF Folders ----------
    public static Uri folderUri(Context c) {
        String s = prefs(c).getString("folder", null);
        return s == null ? null : Uri.parse(s);
    }

    public static Uri txFolderUri(Context c) {
        String s = prefs(c).getString("txFolder", null);
        return s == null ? folderUri(c) : Uri.parse(s);
    }

    public static boolean hasCustomTxFolder(Context c) {
        return prefs(c).getString("txFolder", null) != null;
    }

    public static void clearFolder(Context c) {
        Uri old = folderUri(c);
        prefs(c).edit().remove("folder").apply();
        releaseUri(c, old);
    }

    public static void clearTxFolder(Context c) {
        String raw = prefs(c).getString("txFolder", null);
        Uri old = raw != null ? Uri.parse(raw) : null;
        prefs(c).edit().remove("txFolder").apply();
        releaseUri(c, old);
    }

    private static void releaseUri(Context c, Uri u) {
        if (u == null) return;
        try {
            c.getContentResolver().releasePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) { }
    }

    public static String txFolderLabel(Context c) {
        if (prefs(c).getString("txFolder", null) == null) return "Same as audio";
        return labelOf(txFolderUri(c));
    }

    public static String folderLabel(Context c) {
        Uri u = folderUri(c);
        if (u == null) return "App folder";
        return labelOf(u);
    }

    private static String labelOf(Uri u) {
        if (u == null) return "App folder";
        try {
            String id = DocumentsContract.getTreeDocumentId(u);
            String label = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            if (label.contains("/")) label = label.substring(label.lastIndexOf('/') + 1);
            return label.isEmpty() ? "Phone storage" : label;
        } catch (Exception e) {
            return "Chosen folder";
        }
    }

    /** Copies a recording or transcript into the chosen SAF folder. Call off the main thread. */
    public static boolean copyToFolder(Context c, File f) {
        Uri tree = f.getName().endsWith(".txt") ? txFolderUri(c) : folderUri(c);
        if (tree == null) return false;
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            String mime = f.getName().endsWith(".mp3") ? "audio/mpeg"
                    : f.getName().endsWith(".txt") ? "text/plain" : "audio/mp4";
            Uri out = DocumentsContract.createDocument(c.getContentResolver(), parent, mime, f.getName());
            if (out == null) return false;
            try (InputStream in = new FileInputStream(f);
                 OutputStream os = c.getContentResolver().openOutputStream(out)) {
                if (os == null) return false;
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
