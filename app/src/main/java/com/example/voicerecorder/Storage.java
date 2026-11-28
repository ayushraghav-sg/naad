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

/** Settings and file locations shared by the UI and the recording service. */
public final class Storage {
    public static final String MODEL_TURBO = "whisper-large-v3-turbo";
    public static final String MODEL_LARGE = "whisper-large-v3";

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

    /** Transcription mode: "off", "local", or "groq". */
    public static String txMode(Context c) {
        return prefs(c).getString("txMode", "off");
    }

    /** Minutes per transcript part; -1 = full recording. Default 5. */
    public static int txMinutes(Context c) {
        return prefs(c).getInt("txMinutes", 5);
    }

    /** Groq Whisper model: whisper-large-v3-turbo (default) or whisper-large-v3. */
    public static String groqModel(Context c) {
        String m = prefs(c).getString("groqModel", MODEL_TURBO);
        return MODEL_LARGE.equals(m) ? MODEL_LARGE : MODEL_TURBO;
    }

    /** Groq language ISO-639-1 code, or "" for auto-detect (default). */
    public static String groqLanguage(Context c) {
        String lang = prefs(c).getString("groqLang", "");
        return lang == null ? "" : lang.trim();
    }

    /** Reads the Groq API key, transparently migrating legacy plaintext keys to KeyStore encryption. */
    public static String groqKey(Context c) {
        SharedPreferences sp = prefs(c);
        String raw = sp.getString("groqKey", "");
        if (raw == null || raw.isEmpty()) return "";
        if (!KeyStoreHelper.isEncrypted(raw)) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                String enc = KeyStoreHelper.encrypt(trimmed);
                if (KeyStoreHelper.isEncrypted(enc)) {
                    sp.edit().putString("groqKey", enc).apply();
                }
            }
            return trimmed;
        }
        return KeyStoreHelper.decrypt(raw).trim();
    }

    public static String streamUrl(Context c) { return prefs(c).getString("streamUrl", "").trim(); }

    public static int streamKbps(Context c) { return prefs(c).getInt("streamKbps", 128); }

    public static String streamKey(Context c) {
        String raw = prefs(c).getString("streamKey", "");
        if (raw == null || raw.isEmpty()) return "";
        return KeyStoreHelper.isEncrypted(raw) ? KeyStoreHelper.decrypt(raw).trim() : raw.trim();
    }

    public static void setStream(Context c, String url, String key, int kbps) {
        String k = key == null ? "" : key.trim();
        SharedPreferences.Editor ed = prefs(c).edit()
                .putString("streamUrl", url == null ? "" : url.trim())
                .putInt("streamKbps", kbps);
        if (k.isEmpty()) ed.remove("streamKey");
        else ed.putString("streamKey", KeyStoreHelper.encrypt(k));
        ed.apply();
    }

    public static boolean hasStreamTarget(Context c) { return !streamUrl(c).isEmpty(); }

    public static void setGroqKey(Context c, String key) {
        String trimmed = key == null ? "" : key.trim();
        SharedPreferences.Editor ed = prefs(c).edit();
        if (trimmed.isEmpty()) {
            ed.remove("groqKey");
        } else {
            ed.putString("groqKey", KeyStoreHelper.encrypt(trimmed));
        }
        ed.apply();
    }

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
        Uri old = prefs(c).getString("txFolder", null) != null
                ? Uri.parse(prefs(c).getString("txFolder", null)) : null;
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
