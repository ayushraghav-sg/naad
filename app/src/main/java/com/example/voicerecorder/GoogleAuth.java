package com.example.voicerecorder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java Google Cloud Service Account RS256 JWT signer & OAuth2 access token client.
 * Powers both Vertex AI (cloud-platform scope) and direct Google Docs API (documents scope)
 * without pulling in heavy Google Cloud SDK dependencies.
 */
public final class GoogleAuth {

    public static final String SCOPE_CLOUD = "https://www.googleapis.com/auth/cloud-platform";
    public static final String SCOPE_DOCS = "https://www.googleapis.com/auth/documents";
    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final Pattern DOC_URL_PATTERN = Pattern.compile("/document/(?:u/\\d+/)?d/([a-zA-Z0-9_-]+)");
    private static final Pattern DOC_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{10,}$");

    public static final class ServiceAccount {
        public final String clientEmail;
        public final String privateKeyPem;
        public final String projectId;
        public final String tokenUri;

        public ServiceAccount(String clientEmail, String privateKeyPem, String projectId, String tokenUri) {
            this.clientEmail = clientEmail;
            this.privateKeyPem = privateKeyPem;
            this.projectId = projectId;
            this.tokenUri = (tokenUri == null || tokenUri.isEmpty()) ? DEFAULT_TOKEN_URI : tokenUri;
        }
    }

    private static final class CachedToken {
        final String token;
        final long expiresAtMs;
        CachedToken(String t, long exp) { token = t; expiresAtMs = exp; }
    }

    private static final Map<String, CachedToken> CACHE = new ConcurrentHashMap<>();

    private GoogleAuth() { }

    /** Clears cached OAuth tokens (e.g. after updating Service Account JSON in Settings). */
    public static void clearCache() {
        CACHE.clear();
    }

    /** Extracts a Google Doc ID from either a full Google Docs URL or a raw ID. */
    public static String extractDocumentId(String input) {
        if (input == null) return "";
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return "";
        Matcher m = DOC_URL_PATTERN.matcher(trimmed);
        if (m.find()) return m.group(1);
        if (DOC_ID_PATTERN.matcher(trimmed).matches()) return trimmed;
        return "";
    }

    /** Parses a Google Cloud Service Account JSON string without requiring Android's org.json. */
    public static ServiceAccount parseServiceAccount(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        String email = extractJsonString(json, "client_email");
        String privKey = extractJsonString(json, "private_key");
        String project = extractJsonString(json, "project_id");
        String tokenUri = extractJsonString(json, "token_uri");
        if (email == null || email.isEmpty() || privKey == null || privKey.isEmpty()) {
            return null;
        }
        return new ServiceAccount(email, privKey, project == null ? "" : project, tokenUri);
    }

    /** Minimal pure-Java JSON string-value extractor handling standard escapes (\n, \", \\). */
    static String extractJsonString(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\"";
        int idx = 0;
        while (true) {
            idx = json.indexOf(needle, idx);
            if (idx < 0) return null;
            int p = idx + needle.length();
            while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
            if (p < json.length() && json.charAt(p) == ':') {
                p++;
                while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
                if (p < json.length() && json.charAt(p) == '"') {
                    p++;
                    StringBuilder sb = new StringBuilder();
                    while (p < json.length()) {
                        char ch = json.charAt(p++);
                        if (ch == '\\' && p < json.length()) {
                            char esc = json.charAt(p++);
                            switch (esc) {
                                case 'n': sb.append('\n'); break;
                                case 'r': sb.append('\r'); break;
                                case 't': sb.append('\t'); break;
                                case '"': sb.append('"'); break;
                                case '\\': sb.append('\\'); break;
                                case '/': sb.append('/'); break;
                                case 'u':
                                    if (p + 4 <= json.length()) {
                                        sb.append((char) Integer.parseInt(json.substring(p, p + 4), 16));
                                        p += 4;
                                    }
                                    break;
                                default: sb.append(esc); break;
                            }
                        } else if (ch == '"') {
                            return sb.toString();
                        } else {
                            sb.append(ch);
                        }
                    }
                    return null;
                }
            }
            idx += needle.length();
        }
    }

    /** Signs a Google OAuth2 Service Account JWT assertion using RS256. */
    static String signJwt(ServiceAccount sa, String scope, long nowSeconds) throws Exception {
        String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String claimJson = "{"
                + "\"iss\":\"" + escapeJson(sa.clientEmail) + "\","
                + "\"scope\":\"" + escapeJson(scope) + "\","
                + "\"aud\":\"" + escapeJson(sa.tokenUri) + "\","
                + "\"iat\":" + nowSeconds + ","
                + "\"exp\":" + (nowSeconds + 3600)
                + "}";
        Base64.Encoder b64Url = Base64.getUrlEncoder().withoutPadding();
        String signingInput = b64Url.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
                + "."
                + b64Url.encodeToString(claimJson.getBytes(StandardCharsets.UTF_8));

        PrivateKey key = parsePkcs8PrivateKey(sa.privateKeyPem);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = sig.sign();
        return signingInput + "." + b64Url.encodeToString(signatureBytes);
    }

    private static PrivateKey parsePkcs8PrivateKey(String pem) throws Exception {
        String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(cleaned);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Fetches (or returns cached) OAuth2 Bearer access token for the given Service Account & scope. */
    public static String getAccessToken(String saJson, String scope) throws IOException {
        ServiceAccount sa = parseServiceAccount(saJson);
        if (sa == null) throw new IOException("Invalid Service Account JSON");
        String cacheKey = sa.clientEmail + "|" + scope;
        CachedToken cached = CACHE.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMs - 120_000L > now) {
            return cached.token;
        }
        String jwt;
        try {
            jwt = signJwt(sa, scope, now / 1000L);
        } catch (Exception e) {
            throw new IOException("Could not sign Service Account key: " + e.getMessage(), e);
        }

        HttpURLConnection con = (HttpURLConnection) new URL(sa.tokenUri).openConnection();
        try {
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String body = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8")
                    + "&assertion=" + URLEncoder.encode(jwt, "UTF-8");
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            InputStream stream = code == 200 ? con.getInputStream() : con.getErrorStream();
            String resp = stream != null ? readAll(stream) : "";
            if (code != 200) {
                throw new IOException("Google OAuth failed (" + code + "): " + truncate(resp, 140));
            }
            String token = extractJsonString(resp, "access_token");
            if (token == null || token.isEmpty()) {
                throw new IOException("Google OAuth returned no access_token");
            }
            long expiresInSec = extractJsonLong(resp, "expires_in", 3600L);
            CACHE.put(cacheKey, new CachedToken(token, now + expiresInSec * 1000L));
            return token;
        } finally {
            con.disconnect();
        }
    }

    private static long extractJsonLong(String json, String key, long def) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    public static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
