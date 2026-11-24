/**
 * Naad -> Live Google Docs Bridge (Google Apps Script)
 *
 * Setup (60 seconds):
 * 1. Open https://script.google.com and click "New project".
 * 2. Paste this entire script and optionally set SECRET below (must match Naad Settings).
 * 3. Click Deploy -> New deployment -> Web app:
 *      - Execute as: Me
 *      - Who has access: Anyone
 * 4. Copy the Web App URL (https://script.google.com/macros/s/.../exec) into Naad -> Settings -> Live Google Docs.
 */

const SECRET = ""; // Optional: set a passphrase and enter the same secret in Naad Settings

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents || "{}");
    if (SECRET && data.secret !== SECRET) {
      return jsonOut({ ok: false, error: "Invalid secret" });
    }
    if (data.action === "ping") {
      return jsonOut({ ok: true, message: "Connected to Google Docs" });
    }

    let doc;
    if (data.docId) {
      doc = DocumentApp.openById(data.docId);
    } else {
      const title = (data.title || "Naad Transcript") + " - " +
        Utilities.formatDate(new Date(), Session.getScriptTimeZone(), "dd MMM yyyy HH:mm");
      doc = DocumentApp.create(title);
      doc.getBody().appendParagraph(title).setHeading(DocumentApp.ParagraphHeading.HEADING1);
    }

    const text = (data.text || "").trim();
    if (text) {
      const body = doc.getBody();
      const prefix = data.timestamp ? "[" + data.timestamp + "] " : "";
      body.appendParagraph(prefix + text);
    }
    doc.saveAndClose();

    return jsonOut({
      ok: true,
      docId: doc.getId(),
      docUrl: doc.getUrl()
    });
  } catch (err) {
    return jsonOut({ ok: false, error: String(err) });
  }
}

function jsonOut(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
