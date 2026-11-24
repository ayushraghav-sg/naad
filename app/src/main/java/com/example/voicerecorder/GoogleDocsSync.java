package com.example.voicerecorder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams live transcript chunks into Google Docs in real time.
 * Supports two backends:
 *  1) Google Apps Script Web App (auto-creates a Google Doc per recording in the user's Drive)
 *  2) Direct Google Docs REST API via Google Cloud Service Account JSON (appends to a shared Doc)
 *
 * Private recordings are strictly skipped and never sent over the network.
 */
public final class GoogleDocsSync {

    public interface Listener {
        void onDocLinked(String base, String docUrl);
        void onDocError(String message);
    }

    public interface TestCallback {
        void onResult(boolean ok, String message);
    }

    public static final String APPS_SCRIPT_TEMPLATE =
            "const SECRET = \"\";\n\n"
            + "function doPost(e) {\n"
            + "  try {\n"
            + "    const data = JSON.parse(e.postData.contents || \"{}\");\n"
            + "    if (SECRET && data.secret !== SECRET) return jsonOut({ ok: false, error: \"Invalid secret\" });\n"
            + "    if (data.action === \"ping\") return jsonOut({ ok: true, message: \"Connected to Google Docs\" });\n"
            + "    let doc;\n"
            + "    if (data.docId) {\n"
            + "      doc = DocumentApp.openById(data.docId);\n"
            + "    } else {\n"
            + "      const title = (data.title || \"Naad Transcript\") + \" - \" +\n"
            + "        Utilities.formatDate(new Date(), Session.getScriptTimeZone(), \"dd MMM yyyy HH:mm\");\n"
            + "      doc = DocumentApp.create(title);\n"
            + "      doc.getBody().appendParagraph(title).setHeading(DocumentApp.ParagraphHeading.HEADING1);\n"
            + "    }\n"
            + "    const text = (data.text || \"\").trim();\n"
            + "    if (text) {\n"
            + "      const prefix = data.timestamp ? \"[\" + data.timestamp + \"] \" : \"\";\n"
            + "      doc.getBody().appendParagraph(prefix + text);\n"
            + "    }\n"
            + "    doc.saveAndClose();\n"
            + "    return jsonOut({ ok: true, docId: doc.getId(), docUrl: doc.getUrl() });\n"
            + "  } catch (err) {\n"
            + "    return jsonOut({ ok: false, error: String(err) });\n"
            + "  }\n"
            + "}\n\n"
            + "function jsonOut(obj) {\n"
            + "  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);\n"
            + "}\n";

    private static GoogleDocsSync instance;

    public static synchronized GoogleDocsSync get(Context c) {
        if (instance == null) instance = new GoogleDocsSync(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> apiHeadersWritten = ConcurrentHashMap.newKeySet();
    private volatile Listener listener;

    private GoogleDocsSync(Context c) { app = c; }

    public void setListener(Listener l) { listener = l; }

    private boolean isPrivate(String base) {
        return Storage.prefs(app).getBoolean("private:" + base, false);
    }

    /** Appends a live transcript chunk to Google Docs asynchronously if enabled and non-private. */
    public void appendChunk(String base, String text) {
        if (base == null || text == null) return;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return;
        if (isPrivate(base) || !Storage.isGdocsEnabled(app)) return;
        if (!Transcriber.get(app).isOnline()) return;

        String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        worker.execute(() -> {
            try {
                String url = pushText(base, trimmed, timeStamp);
                if (url != null && !url.isEmpty()) {
                    main.post(() -> {
                        Listener l = listener;
                        if (l != null) l.onDocLinked(base, url);
                    });
                }
            } catch (Exception e) {
                main.post(() -> {
                    Listener l = listener;
                    if (l != null) l.onDocError("Google Docs sync: " + e.getMessage());
                });
            }
        });
    }

    /** Sends an existing recording's full transcript to Google Docs on demand. */
    public void syncExisting(String base, String fullText, TestCallback cb) {
        if (isPrivate(base)) {
            main.post(() -> cb.onResult(false, "Private recordings are never uploaded to Google Docs."));
            return;
        }
        if (!Storage.isGdocsEnabled(app)) {
            main.post(() -> cb.onResult(false, "Configure Live Google Docs in Settings first."));
            return;
        }
        if (!Transcriber.get(app).isOnline()) {
            main.post(() -> cb.onResult(false, "No internet connection."));
            return;
        }
        String trimmed = fullText == null ? "" : fullText.trim();
        if (trimmed.isEmpty()) {
            main.post(() -> cb.onResult(false, "Transcript is empty."));
            return;
        }
        worker.execute(() -> {
            try {
                String url = pushText(base, trimmed, "");
                main.post(() -> {
                    Listener l = listener;
                    if (l != null && url != null && !url.isEmpty()) l.onDocLinked(base, url);
                    cb.onResult(true, "Synced to Google Docs");
                });
            } catch (Exception e) {
                main.post(() -> cb.onResult(false, "Google Docs error: " + e.getMessage()));
            }
        });
    }

    private String pushText(String base, String text, String timeStamp) throws IOException {
        String mode = Storage.gdocsMode(app);
        String title = Formatters.displayName(base + ".mp3");
        if (Storage.GDOCS_SCRIPT.equals(mode)) {
            return pushViaAppsScript(base, title, text, timeStamp);
        } else if (Storage.GDOCS_API.equals(mode)) {
            return pushViaDocsApi(base, title, text, timeStamp);
        }
        return "";
    }

    private String pushViaAppsScript(String base, String title, String text, String timeStamp) throws IOException {
        String scriptUrl = Storage.gdocsScriptUrl(app);
        if (scriptUrl.isEmpty()) throw new IOException("Apps Script URL is empty");
        String secret = Storage.gdocsScriptSecret(app);
        String existingDocId = Storage.gdocIdFor(app, base);
        if (existingDocId.isEmpty()) existingDocId = Storage.gdocsDocId(app);

        String payload = "{"
                + "\"secret\":\"" + GoogleAuth.escapeJson(secret) + "\","
                + "\"action\":\"append\","
                + "\"recordingId\":\"" + GoogleAuth.escapeJson(base) + "\","
                + "\"title\":\"" + GoogleAuth.escapeJson(title) + "\","
                + "\"docId\":\"" + GoogleAuth.escapeJson(existingDocId) + "\","
                + "\"timestamp\":\"" + GoogleAuth.escapeJson(timeStamp) + "\","
                + "\"text\":\"" + GoogleAuth.escapeJson(text) + "\""
                + "}";
        String resp = postAppsScript(scriptUrl, payload);
        String err = GoogleAuth.extractJsonString(resp, "error");
        if (err != null && !err.isEmpty()) throw new IOException(err);
        String docId = GoogleAuth.extractJsonString(resp, "docId");
        String docUrl = GoogleAuth.extractJsonString(resp, "docUrl");
        if ((docUrl == null || docUrl.isEmpty()) && docId != null && !docId.isEmpty()) {
            docUrl = "https://docs.google.com/document/d/" + docId + "/edit";
        }
        if (docId != null && !docId.isEmpty()) {
            Storage.setGdocFor(app, base, docId, docUrl);
        }
        return docUrl == null ? "" : docUrl;
    }

    private String pushViaDocsApi(String base, String title, String text, String timeStamp) throws IOException {
        String docId = Storage.gdocsDocId(app);
        if (docId.isEmpty()) throw new IOException("No Google Doc URL/ID configured");
        String saJson = Storage.vertexServiceAccountJson(app);
        String token = GoogleAuth.getAccessToken(saJson, GoogleAuth.SCOPE_DOCS);

        StringBuilder insert = new StringBuilder();
        if (apiHeadersWritten.add(base)) {
            insert.append("\n\n--- ")
                  .append(title)
                  .append(" (")
                  .append(Formatters.fullDate(System.currentTimeMillis()))
                  .append(") ---\n");
        }
        if (!timeStamp.isEmpty()) {
            insert.append("[").append(timeStamp).append("] ");
        }
        insert.append(text).append("\n");

        String payload = "{\"requests\":[{\"insertText\":{"
                + "\"endOfSegmentLocation\":{\"segmentId\":\"\"},"
                + "\"text\":\"" + GoogleAuth.escapeJson(insert.toString()) + "\""
                + "}}]}";
        postDocsBatchUpdate(docId, token, payload);
        String docUrl = "https://docs.google.com/document/d/" + docId + "/edit";
        Storage.setGdocFor(app, base, docId, docUrl);
        return docUrl;
    }

    /** Verifies Google Docs configuration right from Settings. */
    public void testConnection(String mode, String scriptUrl, String scriptSecret,
                               String docUrlOrId, String saJson, TestCallback cb) {
        worker.execute(() -> {
            try {
                if (Storage.GDOCS_SCRIPT.equals(mode)) {
                    String u = scriptUrl == null ? "" : scriptUrl.trim();
                    if (u.isEmpty()) {
                        main.post(() -> cb.onResult(false, "Enter your Apps Script Web App URL first"));
                        return;
                    }
                    String payload = "{\"secret\":\"" + GoogleAuth.escapeJson(scriptSecret == null ? "" : scriptSecret.trim())
                            + "\",\"action\":\"ping\"}";
                    String resp = postAppsScript(u, payload);
                    String err = GoogleAuth.extractJsonString(resp, "error");
                    if (err != null && !err.isEmpty()) {
                        main.post(() -> cb.onResult(false, "Apps Script error: " + err));
                    } else if (resp.contains("\"ok\":true") || resp.contains("\"ok\": true")) {
                        main.post(() -> cb.onResult(true, "Connected to Google Docs Apps Script"));
                    } else {
                        main.post(() -> cb.onResult(false, "Unexpected response. Ensure Web App access is set to Anyone."));
                    }
                } else if (Storage.GDOCS_API.equals(mode)) {
                    String docId = GoogleAuth.extractDocumentId(docUrlOrId);
                    if (docId.isEmpty()) {
                        main.post(() -> cb.onResult(false, "Paste a valid Google Doc URL or ID"));
                        return;
                    }
                    String token = GoogleAuth.getAccessToken(saJson, GoogleAuth.SCOPE_DOCS);
                    HttpURLConnection c = (HttpURLConnection) new URL(
                            "https://docs.googleapis.com/v1/documents/" + docId + "?fields=title").openConnection();
                    try {
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(15000);
                        c.setRequestMethod("GET");
                        c.setRequestProperty("Authorization", "Bearer " + token);
                        int code = c.getResponseCode();
                        if (code == 200) {
                            String resp = GoogleAuth.readAll(c.getInputStream());
                            String docTitle = GoogleAuth.extractJsonString(resp, "title");
                            main.post(() -> cb.onResult(true,
                                    "Connected to \"" + (docTitle != null ? docTitle : docId) + "\""));
                        } else if (code == 403 || code == 404) {
                            GoogleAuth.ServiceAccount sa = GoogleAuth.parseServiceAccount(saJson);
                            String email = sa != null ? sa.clientEmail : "your service account";
                            main.post(() -> cb.onResult(false,
                                    "Cannot access Doc (" + code + "). Share it as Editor with " + email));
                        } else {
                            main.post(() -> cb.onResult(false, "Google Docs API error " + code));
                        }
                    } finally {
                        c.disconnect();
                    }
                } else {
                    main.post(() -> cb.onResult(false, "Enable Apps Script or Docs API first"));
                }
            } catch (Exception e) {
                main.post(() -> cb.onResult(false, "Test failed: " + e.getMessage()));
            }
        });
    }

    /**
     * Posts JSON to a Google Apps Script Web App and manually follows its 302 redirect via GET
     * (required because Apps Script redirects POST responses across hosts to script.googleusercontent.com).
     */
    private static String postAppsScript(String scriptUrl, String jsonPayload) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(scriptUrl).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc != null && !loc.isEmpty()) {
                    return getFollowingRedirects(loc);
                }
            }
            InputStream stream = code == 200 ? c.getInputStream() : c.getErrorStream();
            String body = stream != null ? GoogleAuth.readAll(stream) : "";
            if (code != 200) throw new IOException("HTTP " + code);
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static String getFollowingRedirects(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            InputStream stream = code == 200 ? c.getInputStream() : c.getErrorStream();
            String body = stream != null ? GoogleAuth.readAll(stream) : "";
            if (code != 200) throw new IOException("HTTP " + code);
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static void postDocsBatchUpdate(String docId, String bearerToken, String jsonPayload) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://docs.googleapis.com/v1/documents/" + docId + ":batchUpdate").openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + bearerToken);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                String err = c.getErrorStream() != null ? GoogleAuth.readAll(c.getErrorStream()) : "";
                String msg = GoogleAuth.extractJsonString(err, "message");
                throw new IOException("HTTP " + code + (msg != null && !msg.isEmpty() ? ": " + msg : ""));
            }
        } finally {
            c.disconnect();
        }
    }
}
