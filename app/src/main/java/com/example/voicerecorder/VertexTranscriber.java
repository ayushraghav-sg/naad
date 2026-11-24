package com.example.voicerecorder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Transcribes audio chunks via Google Vertex AI Gemini models (gemini-2.5-flash / gemini-2.5-pro).
 * Supports both:
 *  - Express Mode API Key (x-goog-api-key header)
 *  - Google Cloud Service Account JSON (RS256 JWT -> OAuth2 Bearer token + regional/global endpoint)
 */
public final class VertexTranscriber {

    public interface TestCallback {
        void onResult(boolean ok, String message);
    }

    /** Inline base64 audio safely stays under Vertex's 20 MB request payload cap. */
    public static final long DIRECT_LIMIT_BYTES = 12L * 1024 * 1024;

    private VertexTranscriber() { }

    static String buildEndpoint(String authMode, String projectId, String location, String model) {
        String m = (model == null || model.isEmpty()) ? Storage.VERTEX_MODEL_FLASH : model;
        if (Storage.VERTEX_AUTH_SA.equals(authMode)) {
            String loc = (location == null || location.isEmpty()) ? "global" : location.trim();
            String host = "global".equals(loc)
                    ? "https://aiplatform.googleapis.com"
                    : "https://" + loc + "-aiplatform.googleapis.com";
            return host + "/v1/projects/" + projectId + "/locations/" + loc
                    + "/publishers/google/models/" + m + ":generateContent";
        }
        return "https://aiplatform.googleapis.com/v1/publishers/google/models/" + m + ":generateContent";
    }

    /** Transcribes a single audio chunk file using the configured Vertex AI credentials. */
    public static String transcribe(Context app, File audioFile) throws IOException, Transcriber.ApiException {
        String authMode = Storage.vertexAuthMode(app);
        String model = Storage.vertexModel(app);
        String location = Storage.vertexLocation(app);
        String apiKey = Storage.vertexApiKey(app);
        String saJson = Storage.vertexServiceAccountJson(app);
        String lang = Storage.groqLanguage(app);

        String endpoint;
        String authHeader = null;
        String keyHeader = null;
        if (Storage.VERTEX_AUTH_SA.equals(authMode)) {
            GoogleAuth.ServiceAccount sa = GoogleAuth.parseServiceAccount(saJson);
            if (sa == null || sa.projectId.isEmpty()) {
                throw new Transcriber.ApiException("Add a valid Service Account JSON in Settings for Vertex AI.");
            }
            endpoint = buildEndpoint(authMode, sa.projectId, location, model);
            authHeader = "Bearer " + GoogleAuth.getAccessToken(saJson, GoogleAuth.SCOPE_CLOUD);
        } else {
            if (apiKey.isEmpty()) {
                throw new Transcriber.ApiException("Add your Vertex AI API key in Settings to transcribe.");
            }
            endpoint = buildEndpoint(authMode, "", location, model);
            keyHeader = apiKey;
        }

        byte[] audioBytes = readBytes(audioFile);
        String b64 = Base64.getEncoder().encodeToString(audioBytes);
        String mime = audioFile.getName().endsWith(".m4a") ? "audio/mp4" : "audio/mpeg";
        String langHint = "en".equals(lang) ? " The audio is in English."
                : "hi".equals(lang) ? " The audio is in Hindi."
                : "";
        String prompt = "Transcribe this audio recording verbatim." + langHint
                + " Return only the spoken text with natural punctuation and paragraph breaks."
                + " Do not add titles, commentary, markdown, or timestamps. If there is no speech, return an empty response.";

        String payload = "{"
                + "\"contents\":[{\"role\":\"user\",\"parts\":["
                + "{\"inlineData\":{\"mimeType\":\"" + mime + "\",\"data\":\"" + b64 + "\"}},"
                + "{\"text\":\"" + GoogleAuth.escapeJson(prompt) + "\"}"
                + "]}],"
                + "\"generationConfig\":{\"temperature\":0.1}"
                + "}";

        String resp = postJson(endpoint, payload, authHeader, keyHeader, 120000);
        return extractTranscriptText(resp);
    }

    /** Verifies Vertex AI credentials by sending a tiny test prompt on a background thread. */
    public static void testConnection(String authMode, String apiKey, String saJson,
                                      String location, String model, TestCallback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String endpoint;
                String authHeader = null;
                String keyHeader = null;
                if (Storage.VERTEX_AUTH_SA.equals(authMode)) {
                    GoogleAuth.ServiceAccount sa = GoogleAuth.parseServiceAccount(saJson);
                    if (sa == null) {
                        main.post(() -> cb.onResult(false, "Invalid Service Account JSON"));
                        return;
                    }
                    if (sa.projectId.isEmpty()) {
                        main.post(() -> cb.onResult(false, "Service Account JSON is missing project_id"));
                        return;
                    }
                    endpoint = buildEndpoint(authMode, sa.projectId, location, model);
                    authHeader = "Bearer " + GoogleAuth.getAccessToken(saJson, GoogleAuth.SCOPE_CLOUD);
                } else {
                    String k = apiKey == null ? "" : apiKey.trim();
                    if (k.isEmpty()) {
                        main.post(() -> cb.onResult(false, "Enter a Vertex AI API key first"));
                        return;
                    }
                    endpoint = buildEndpoint(authMode, "", location, model);
                    keyHeader = k;
                }
                String payload = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Reply OK\"}]}],"
                        + "\"generationConfig\":{\"maxOutputTokens\":8,\"temperature\":0}}";
                postJson(endpoint, payload, authHeader, keyHeader, 20000);
                main.post(() -> cb.onResult(true, "Connected to Vertex AI (" + model + ")"));
            } catch (Transcriber.ApiException e) {
                main.post(() -> cb.onResult(false, e.getMessage()));
            } catch (Exception e) {
                main.post(() -> cb.onResult(false, "Connection failed: " + e.getMessage()));
            }
        }, "vertex-test").start();
    }

    private static String postJson(String endpoint, String payload, String authHeader,
                                   String keyHeader, int readTimeoutMs) throws IOException, Transcriber.ApiException {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(readTimeoutMs);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (authHeader != null) c.setRequestProperty("Authorization", authHeader);
            if (keyHeader != null) c.setRequestProperty("x-goog-api-key", keyHeader);

            byte[] bodyBytes = payload.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bodyBytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bodyBytes);
            }
            int code = c.getResponseCode();
            if (code == 200) {
                return GoogleAuth.readAll(c.getInputStream());
            }
            String err = c.getErrorStream() != null ? GoogleAuth.readAll(c.getErrorStream()) : "";
            if (code == 401 || code == 403) {
                throw new Transcriber.ApiException("Vertex AI auth failed (" + code + "). Check key/Service Account permissions.");
            }
            if (code == 429) {
                throw new Transcriber.ApiException("Vertex AI quota reached. Try again shortly.");
            }
            if (code == 404) {
                throw new Transcriber.ApiException("Vertex AI model or region not found. Try region 'global' or 'us-central1'.");
            }
            String msg = GoogleAuth.extractJsonString(err, "message");
            throw new Transcriber.ApiException("Vertex AI error " + code
                    + (msg != null && !msg.isEmpty() ? ": " + msg : ""));
        } finally {
            c.disconnect();
        }
    }

    private static String extractTranscriptText(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) return "";
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            if (content == null) return "";
            JSONArray parts = content.optJSONArray("parts");
            if (parts == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                String t = parts.getJSONObject(i).optString("text", "");
                if (!t.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f);
             ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(f.length(), 16 * 1024 * 1024))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
