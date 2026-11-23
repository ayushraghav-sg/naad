package com.example.voicerecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends audio chunks to Groq (Whisper) one at a time, in order, and writes text files.
 * Chunks wait in a private "pending" folder. If there is no internet, nothing is sent
 * until the user approves it in the app.
 */
public final class Transcriber {

    public interface Listener {
        void onTranscript(String base, String text);
        void onStatus(String status);
        void onNeedsApproval(int waiting);
    }

    private static final String URL_GROQ = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String MODEL = "whisper-large-v3-turbo";
    static final Pattern NAME = Pattern.compile("(.+)__part(\\d+)(_last)?\\.(mp3|m4a)");

    private static Transcriber instance;

    public static synchronized Transcriber get(Context c) {
        if (instance == null) instance = new Transcriber(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile Listener listener;

    private Transcriber(Context c) { app = c; }

    public void setListener(Listener l) { listener = l; }

    private SharedPreferences prefs() { return Storage.prefs(app); }

    public static File pendingDir(Context c) {
        File d = new File(c.getFilesDir(), "pending");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public List<File> pending() {
        File[] arr = pendingDir(app).listFiles((d, n) -> NAME.matcher(n).matches());
        List<File> out = new ArrayList<>();
        if (arr != null) {
            Arrays.sort(arr, (a, b) -> a.getName().compareTo(b.getName()));
            out.addAll(Arrays.asList(arr));
        }
        return out;
    }

    public boolean needsApproval() { return prefs().getBoolean("txApproval", false); }

    public boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /** Called when a new chunk file is ready in the pending folder. */
    public void chunkReady() {
        if (needsApproval()) {
            int n = pending().size();
            post(() -> { Listener l = listener; if (l != null) l.onNeedsApproval(n); });
            return;
        }
        kick();
    }

    /** Start sending if allowed. Safe to call any time. */
    public void kick() {
        if (running || needsApproval() || pending().isEmpty()) return;
        running = true;
        worker.execute(this::drain);
    }

    /** Last part was too short to send: copy the finished transcript once earlier parts are done. */
    public void finishWithoutLastPart(String base) {
        worker.execute(() -> {
            File combined = new File(Storage.recDir(app), base + ".txt");
            if (combined.exists()) Storage.copyToFolder(app, combined);
        });
    }

    public void approveAndSend() {
        prefs().edit().putBoolean("txApproval", false).apply();
        kick();
    }

    public void discardAll() {
        for (File f : pending()) f.delete();
        prefs().edit().putBoolean("txApproval", false).apply();
    }

    /** Queue an existing recording to be transcribed as a whole. */
    public boolean queueWholeFile(File rec) {
        if (rec.length() > 24L * 1024 * 1024) return false; // Groq free tier limit is 25 MB
        String ext = rec.getName().endsWith(".m4a") ? "m4a" : "mp3";
        File dst = new File(pendingDir(app), stem(rec.getName()) + "__part01_last." + ext);
        try (InputStream in = new FileInputStream(rec); FileOutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (IOException e) {
            dst.delete();
            return false;
        }
        chunkReady();
        return true;
    }

    private void drain() {
        try {
            while (true) {
                List<File> p = pending();
                if (p.isEmpty()) { status(null); break; }
                if (!isOnline()) {
                    prefs().edit().putBoolean("txApproval", true).apply();
                    status("No internet. " + p.size() + (p.size() == 1 ? " part is" : " parts are") + " waiting to be sent.");
                    int n = p.size();
                    post(() -> { Listener l = listener; if (l != null) l.onNeedsApproval(n); });
                    break;
                }
                String key = prefs().getString("groqKey", "");
                if (key == null || key.trim().isEmpty()) { status("Add your Groq API key in Settings to transcribe."); break; }

                File f = p.get(0);
                Matcher m = NAME.matcher(f.getName());
                if (!m.matches()) { f.delete(); continue; }
                String base = m.group(1);
                int part = Integer.parseInt(m.group(2));
                boolean last = m.group(3) != null;

                status("Transcribing part " + part + "...");
                String text;
                try {
                    text = send(f, key.trim());
                } catch (ApiException e) {
                    status(e.getMessage());
                    break; // keep the chunk; user can retry later
                } catch (IOException e) {
                    prefs().edit().putBoolean("txApproval", true).apply();
                    status("Connection failed. Parts are waiting to be sent.");
                    int n = pending().size();
                    post(() -> { Listener l = listener; if (l != null) l.onNeedsApproval(n); });
                    break;
                }
                writeText(base, part, last, text == null ? "" : text.trim());
                f.delete();
                String shown = text == null ? "" : text.trim();
                post(() -> { Listener l = listener; if (l != null) l.onTranscript(base, shown); });
            }
        } finally {
            running = false;
        }
    }

    public void writeText(String base, int part, boolean last, String text) {
        File dir = Storage.recDir(app);
        File combined = new File(dir, base + ".txt");
        boolean hasText = combined.exists() && combined.length() > 0;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(combined, true), StandardCharsets.UTF_8)) {
            if (hasText && !text.isEmpty()) w.write("\n\n");
            w.write(text);
        } catch (IOException ignored) { }

        boolean single = last && part == 1;
        if (!single) {
            File pf = new File(dir, base + String.format(java.util.Locale.US, "_part%02d.txt", part));
            try (Writer w = new OutputStreamWriter(new FileOutputStream(pf), StandardCharsets.UTF_8)) {
                w.write(text);
            } catch (IOException ignored) { }
            Storage.copyToFolder(app, pf);
        }
        if (last) Storage.copyToFolder(app, combined);
    }

    static class ApiException extends Exception {
        ApiException(String m) { super(m); }
    }

    private String send(File f, String key) throws IOException, ApiException {
        String boundary = "----naad" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(URL_GROQ).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setChunkedStreamingMode(64 * 1024);

        String mime = f.getName().endsWith(".m4a") ? "audio/mp4" : "audio/mpeg";
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            field(out, boundary, "model", MODEL);
            field(out, boundary, "language", "en");
            field(out, boundary, "response_format", "text");
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + f.getName() + "\"\r\n");
            out.writeBytes("Content-Type: " + mime + "\r\n\r\n");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            out.writeBytes("\r\n--" + boundary + "--\r\n");
        }

        int code = c.getResponseCode();
        if (code == 200) return read(c.getInputStream());
        String body = c.getErrorStream() != null ? read(c.getErrorStream()) : "";
        if (code == 401) throw new ApiException("Groq API key is not valid. Check it in Settings.");
        if (code == 429) throw new ApiException("Groq limit reached. Try again later from the recording's menu.");
        if (code == 413) throw new ApiException("Part too large for Groq.");
        throw new ApiException("Groq error " + code + (body.length() > 120 ? "" : ": " + body));
    }

    private static void field(DataOutputStream out, String b, String name, String value) throws IOException {
        out.writeBytes("--" + b + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private static String read(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Used by the on-device engine to update the screen. */
    public void publish(String base, String text) {
        post(() -> { Listener l = listener; if (l != null) l.onTranscript(base, text); });
    }

    public void status(String s) {
        post(() -> { Listener l = listener; if (l != null) l.onStatus(s); });
    }

    private void post(Runnable r) { main.post(r); }

    public static String stem(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }
}
