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

## Live streaming (RTMP)
- Stream the recording's audio live to any RTMP or RTMPS server while it records.
- In Settings > Live stream, enter the server URL (for example `rtmp://server/live`) and the stream key. The key can also be pasted at the end of the URL.
- Turn on **Live** in the recorder card before recording. The status line shows LIVE, CATCHING UP, OFFLINE (with seconds buffered) or CONNECTING.
- Audio only (AAC, 64 or 128 kbps, mono 44.1 kHz). Platforms that require video, such as YouTube Live, will not accept an audio-only stream.
- The key is stored encrypted on the phone. Private recordings are never streamed.

### Continuous stream through internet drops
The stream is built for servers that transcribe or save it, so no audio is skipped:
- Encoding runs independently of the network. If the internet drops, audio keeps being buffered on the phone (up to 30 minutes) and recording continues.
- On reconnect, buffered audio is sent first, faster than real time, until the stream has caught up. Timestamps run from the start of the recording and never reset, so the server receives one continuous timeline.
- Naad asks the server to confirm receipt every 32 KB. Anything sent but not confirmed when the connection dropped is sent again, so audio "in the air" is not lost.
- A frozen connection (no error, but nothing arriving) is detected within about 10 seconds and reconnected.
- With servers that never send receipts, the last 20 seconds are resent after a reconnect, so a few seconds may arrive twice but nothing is missing.
- When recording stops, Naad keeps delivering any buffered audio for up to a minute before closing the stream.
- Pauses are sent as silence so the stream stays up.

## Build
Built with Code on the Go on Android (AGP 8.7.3, Gradle 8.10.2, compileSdk/targetSdk 35). Open the project folder and build.
The first build needs internet to download the libraries:
- `com.github.axet:lame` (MP3 encoder)
- `com.alphacephei:vosk-android` (`0.3.75`) and `net.java.dev.jna:jna` (`5.19.1`, 16 KB page-size compatible)

Requirements: Android 8.0 (API 26) or newer.

## Licences
LAME is LGPL. Vosk is Apache 2.0. Check them before publishing the app on the Play Store.
