# Naad

A simple Android voice recorder with live transcription.

## Features
- Records MP3 (LAME encoder) or M4A, with Voice (64 kbps) and High (128 kbps) quality
- Live waveform, pause and resume, timer, free-space estimate
- Background recording with a notification (Pause, Resume, Stop and save)
- Recordings list with playback, seek bar, skip 10 seconds, sorting
- Share audio, transcript text, transcript files, or both
- Send on WhatsApp as a document (keeps the file name and quality)
- Copies recordings and transcripts to folders you choose (separate folders for audio and transcripts)
- Editable file name, for example `Recording_23-11-2026_09-22-40AM.mp3`

## Transcription
- **On device (private):** Vosk speech recognition, works offline. Choose the Small (40 MB) or Better (128 MB) English model in Settings.
- **Groq (cloud):** Whisper via the Groq API, most accurate. Add your own API key in Settings (free key at console.groq.com).
- Transcript is saved every 1, 5, 10 or 15 minutes, or once for the full recording.
- **Private** switch: that recording is only ever transcribed on the phone.
- If parts were recorded while offline, the app asks before sending them to Groq.

The Groq API key is entered in the app and stored only on the phone. It is not part of this code.

## Build
Built with Code on the Go on Android. Open the project folder and build.
The first build needs internet to download the libraries:
- `com.github.axet:lame` (MP3 encoder)
- `com.alphacephei:vosk-android` and `net.java.dev.jna:jna` (on-device speech recognition)

Requirements: Android 8.0 (API 26) or newer.

## Licences
LAME is LGPL. Vosk is Apache 2.0. Check them before publishing the app on the Play Store.
