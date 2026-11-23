package com.example.voicerecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Settings and file locations shared by the screen and the recording service. */
public final class Storage {
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

    public static String prefix(Context c) {
        String p = prefs(c).getString("prefix", "Recording");
        p = p == null ? "" : p.trim();
        return p.isEmpty() ? "Recording" : p;
    }

    public static String cleanName(String s) {
        return s.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** e.g. Recording_22-11-2026_05-07-32PM */
    public static String newName(Context c, boolean mp3) {
        String stamp = new SimpleDateFormat("dd-MM-yyyy_hh-mm-ssa", Locale.ENGLISH).format(new Date());
        return cleanName(prefix(c)) + "_" + stamp + (mp3 ? ".mp3" : ".m4a");
    }

    /** Transcription: "off" or "groq". */
    public static String txMode(Context c) { return prefs(c).getString("txMode", "off"); }

    /** Minutes per transcript part; -1 = full recording. Default 5. */
    public static int txMinutes(Context c) { return prefs(c).getInt("txMinutes", 5); }

    public static Uri folderUri(Context c) {
        String s = prefs(c).getString("folder", null);
        return s == null ? null : Uri.parse(s);
    }

    public static Uri txFolderUri(Context c) {
        String s = prefs(c).getString("txFolder", null);
        return s == null ? folderUri(c) : Uri.parse(s);
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
        String id = DocumentsContract.getTreeDocumentId(u);
        String label = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (label.contains("/")) label = label.substring(label.lastIndexOf('/') + 1);
        return label.isEmpty() ? "Phone storage" : label;
    }

    /** Copies a recording into the chosen folder. Call off the main thread. */
    public static boolean copyToFolder(Context c, File f) {
        Uri tree = f.getName().endsWith(".txt") ? txFolderUri(c) : folderUri(c);
        if (tree == null) return false;
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            Uri out = DocumentsContract.createDocument(c.getContentResolver(), parent,
                    f.getName().endsWith(".mp3") ? "audio/mpeg" : f.getName().endsWith(".txt") ? "text/plain" : "audio/mp4", f.getName());
            if (out == null) return false;
            try (InputStream in = new FileInputStream(f);
                 OutputStream os = c.getContentResolver().openOutputStream(out)) {
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
