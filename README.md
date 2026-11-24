# Naad

A simple, privacy-first Android voice recorder with live transcription.

## Features
- Records MP3 (LAME encoder) or M4A (AAC via Android `MediaCodec`), with Voice (64 kbps) and High (128 kbps) quality
- Live waveform, pause and resume, timer, free-space estimate, and background low-storage protection
- Background recording with a notification (Pause, Resume, Stop and save) and automatic pause/resume on phone calls or audio focus changes
- Recordings list with search (names and transcripts), sorting, background playback, seek bar, skip 10 seconds, and playback speed (`1x`, `1.25x`, `1.5x`, `2x`)
- Share audio, transcript text, transcript files, or both
- Send on WhatsApp as a document (keeps the file name and quality)
- Copies recordings and transcripts to folders you choose (separate folders for audio and transcripts, with Reset option)
- Editable file name, for example `Recording_23-11-2026_09-22-40AM.mp3`
- Light and Dark theme support (follows system theme)

## Transcription
- **Works with both MP3 and M4A recordings.**
- **On device (private):** Vosk speech recognition, works offline with an anti-aliased 16 kHz resampler. Choose the Small (40 MB) or Better (128 MB) English model in Settings.
- **Groq (cloud):** Whisper via the Groq API (`whisper-large-v3-turbo` or `whisper-large-v3`, Auto/English/Hindi language). Add and test your own API key in Settings (free key at console.groq.com). Large recordings (>24 MB) are automatically split into chunks when sent to Groq.
- Transcript is saved every 1, 5, 10 or 15 minutes, or once for the full recording.
- **Private** switch: that recording is only ever transcribed on the phone.
- Re-transcribe any existing recording later (for example, upgrade an offline transcript with Groq).
- If parts were recorded while offline, the app asks before sending them to Groq.

The Groq API key is encrypted with the Android KeyStore on the phone and excluded from cloud backups. It is not part of this code.

## Build
Built with Code on the Go on Android (AGP 8.7.3, Gradle 8.10.2, compileSdk/targetSdk 35). Open the project folder and build.
The first build needs internet to download the libraries:
- `com.github.axet:lame` (MP3 encoder)
- `com.alphacephei:vosk-android` (`0.3.75`) and `net.java.dev.jna:jna` (`5.19.1`, 16 KB page-size compatible)

Requirements: Android 8.0 (API 26) or newer.

## Licences
LAME is LGPL. Vosk is Apache 2.0. Check them before publishing the app on the Play Store.
