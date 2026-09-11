# VocaNote Android

VocaNote is an Android voice-recorder client for recording meetings, uploading audio to a backend, viewing transcripts/summaries, playing back audio, and sharing transcript text or the original audio file.

## Current app state

- Native Android app written in Kotlin with programmatic Views.
- Foreground-service based recording.
- Automatic upload after stop.
- Recording list/detail screens.
- Transcript, summary, and key-note tabs.
- Audio playback with seek controls and transcript follow/highlight.
- Share menu for current tab text or the original `.m4a` audio file.

## Configuration

Secrets are intentionally **not committed**.

Create `local.properties` in the project root:

```properties
sdk.dir=/path/to/android/sdk
vocanote.serverUrl=https://your-server.example.com
vocanote.uploadToken=YOUR_UPLOAD_TOKEN
```

A template is provided in `local.properties.example`.

## Build

```bash
gradle --no-daemon assembleDebug
```

The debug APK will be created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Important notes for reviewers

This repository currently contains the Android client only. The backend/STT/semantic processing service is separate. Known product-quality gaps compared with ClovaNote-style apps include:

- STT quality on noisy multi-speaker meetings needs improvement.
- Speaker diarization is not yet implemented in the Android client data model/UI.
- Transcript segments are still too granular compared with polished speaker-paragraph transcript UX.
- Backend should expose higher-quality speaker-grouped transcript blocks for the client to render.

## Security

- Upload tokens are supplied via `local.properties` and injected into `BuildConfig` at build time.
- `local.properties`, build outputs, APKs, keystores, and IDE files are ignored by git.
