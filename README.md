# Naad

A simple, privacy-first Android voice recorder with live transcription and real-time Google Docs sync.

## Features
- Records MP3 (LAME encoder) or M4A (AAC via Android `MediaCodec`), with Voice (64 kbps) and High (128 kbps) quality
- Live waveform, pause and resume, timer, free-space estimate, and background low-storage protection
- Background recording with a notification (Pause, Resume, Stop and save) and automatic pause/resume on phone calls or audio focus changes
- Recordings list with search (names and transcripts), sorting, background playback, seek bar, skip 10 seconds, and playback speed (`1x`, `1.25x`, `1.5x`, `2x`)
- Share audio, transcript text, transcript files, or both; send on WhatsApp as a document
- Copies recordings and transcripts to folders you choose (separate folders for audio and transcripts, with Reset option)
- Light and Dark theme support (follows system theme), Android 16 (API 36) edge-to-edge & predictive back

## Transcription
Works with **both MP3 and M4A** recordings, saving/syncing every **30s**, **1, 5, 10, or 15 minutes**, or once for the full recording:
- **On device (private):** Offline Vosk speech recognition with an anti-aliased 16 kHz resampler (Small 40 MB or Better 128 MB English model).
- **Google Vertex AI (cloud):** Gemini audio (`gemini-2.5-flash` or `gemini-2.5-pro`, Auto / English / Hindi). Supports both **Express Mode API Key** and **Google Cloud Service Account JSON** (import `.json` or paste, `global` / `us-central1` / `asia-south1`).
- **Groq (cloud):** Whisper (`whisper-large-v3-turbo` or `whisper-large-v3`, Auto / English / Hindi). Large recordings are automatically split into chunks.
- **Private** switch: recordings marked Private are only ever transcribed on-device and are **never** uploaded to Vertex AI, Groq, or Google Docs.
- Re-transcribe any existing recording later with On-device, Vertex AI, or Groq.

## Live Transcript to Google Docs
Stream live transcripts directly into Google Docs as you record (works with On-device, Vertex AI, and Groq; skipped for Private recordings):
- **Option 1 — Apps Script Web App (easiest, auto-creates a Doc per recording):**
  1. In Naad -> **Settings -> Live Google Docs -> Apps Script**, tap **Copy Apps Script** (or copy `docs/google_apps_script.js`).
  2. Paste at [script.google.com](https://script.google.com) -> **Deploy -> New deployment -> Web app** (*Execute as: Me*, *Who has access: Anyone*).
  3. Paste the Web App URL into Naad and tap **Test**.
- **Option 2 — Direct Google Docs REST API (via Service Account JSON):**
  1. Enable the **Google Docs API** in your Google Cloud project and import your Service Account `.json` in Naad Settings.
  2. Share a Google Doc as **Editor** with the Service Account email shown in Settings, paste the Doc URL into Naad, and tap **Test**.
- Once linked, tap **Open live Google Doc** in the transcript viewer (or **Open in Google Docs** in the recording menu).

All API keys, Service Account JSON, and webhook URLs are encrypted on-device with the Android KeyStore and excluded from cloud backups.

## Build & Releases
Built automatically via GitHub Actions (AGP 8.11, Gradle 8.13, JDK 17, `compileSdk`/`targetSdk` 36, `minSdk` 26). Push a `v*` tag (or run **Build and Release APK** from the Actions tab) to run unit tests and publish a downloadable APK to GitHub Releases.

## Licences
LAME is LGPL. Vosk is Apache 2.0. Check them before publishing the app on the Play Store.
