package com.example.voicerecorder;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GoogleAuthTest {

    @Test
    public void extractDocumentId_handlesUrlsAndRawIds() {
        assertEquals("1AbCdEfGhIjKlMnOpQrStUvWxYz",
                GoogleAuth.extractDocumentId("https://docs.google.com/document/d/1AbCdEfGhIjKlMnOpQrStUvWxYz/edit?usp=sharing"));
        assertEquals("1AbCdEfGhIjKlMnOpQrStUvWxYz",
                GoogleAuth.extractDocumentId("https://docs.google.com/document/u/0/d/1AbCdEfGhIjKlMnOpQrStUvWxYz/edit"));
        assertEquals("1AbCdEfGhIjKlMnOpQrStUvWxYz",
                GoogleAuth.extractDocumentId("1AbCdEfGhIjKlMnOpQrStUvWxYz"));
        assertEquals("", GoogleAuth.extractDocumentId("not a url"));
        assertEquals("", GoogleAuth.extractDocumentId(""));
    }

    @Test
    public void parseServiceAccount_andSignJwt_producesVerifiableRs256Signature() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        String json = "{\n"
                + "  \"type\": \"service_account\",\n"
                + "  \"project_id\": \"my-gcp-project\",\n"
                + "  \"client_email\": \"naad-bot@my-gcp-project.iam.gserviceaccount.com\",\n"
                + "  \"private_key\": \"" + GoogleAuth.escapeJson(pem) + "\",\n"
                + "  \"token_uri\": \"https://oauth2.googleapis.com/token\"\n"
                + "}";

        GoogleAuth.ServiceAccount sa = GoogleAuth.parseServiceAccount(json);
        assertNotNull(sa);
        assertEquals("my-gcp-project", sa.projectId);
        assertEquals("naad-bot@my-gcp-project.iam.gserviceaccount.com", sa.clientEmail);

        String jwt = GoogleAuth.signJwt(sa, GoogleAuth.SCOPE_CLOUD, 1700000000L);
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(kp.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        byte[] sigBytes = Base64.getUrlDecoder().decode(parts[2]);
        assertTrue(verifier.verify(sigBytes));
    }

    @Test
    public void parseServiceAccount_rejectsIncompleteJson() {
        assertNull(GoogleAuth.parseServiceAccount("{}"));
        assertNull(GoogleAuth.parseServiceAccount("{\"client_email\":\"a@b.com\"}"));
    }

    @Test
    public void vertexEndpoint_matchesExpressGlobalAndRegionalFormats() {
        assertEquals(
                "https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-2.5-flash:generateContent",
                VertexTranscriber.buildEndpoint(Storage.VERTEX_AUTH_KEY, "", "global", Storage.VERTEX_MODEL_FLASH));
        assertEquals(
                "https://aiplatform.googleapis.com/v1/projects/p1/locations/global/publishers/google/models/gemini-2.5-pro:generateContent",
                VertexTranscriber.buildEndpoint(Storage.VERTEX_AUTH_SA, "p1", "global", Storage.VERTEX_MODEL_PRO));
        assertEquals(
                "https://asia-south1-aiplatform.googleapis.com/v1/projects/p1/locations/asia-south1/publishers/google/models/gemini-2.5-flash:generateContent",
                VertexTranscriber.buildEndpoint(Storage.VERTEX_AUTH_SA, "p1", "asia-south1", Storage.VERTEX_MODEL_FLASH));
    }
}
