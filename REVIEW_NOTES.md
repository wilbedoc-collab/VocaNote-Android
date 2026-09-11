# Review notes

## Context

This repository is published for independent engineering and race-safety review. It contains the Phase88A Android recorder/upload/finalization implementation used by the current device build.

## High-priority review areas

1. **Security/configuration**
   - Confirm no secrets are committed.
   - Confirm `BuildConfig.VOCANOTE_UPLOAD_TOKEN` is populated only from local build config.

2. **Recording lifecycle and identity fencing**
   - Review the serialized `RECORDING → STOPPING → FINALIZING → IDLE` lifecycle.
   - Verify START, STOP, finalization publication, and compare-and-clear use exact path + client recording identity.
   - Verify stale Activities, Services, and workers cannot mutate another recording identity.

3. **Persistence and upload safety**
   - Review fixed-length streaming multipart upload and bounded buffers.
   - Verify per-record unique WorkManager jobs, concurrency limits, process-death recovery, and queue-wide auth handling.
   - Verify Forget/tombstone and finalizer registration share an atomic fence.

4. **Transcript UX**
   - Detail screen should evolve from raw short segments to speaker-grouped paragraphs.
   - Compare with ClovaNote-like transcript cards: speaker label, timestamp, readable paragraph block, current playback highlight.

5. **Audio sharing**
   - `공유` currently offers text sharing and audio-file sharing.
   - Audio is downloaded from `/api/recordings/{id}/download/audio`, cached under app cache, and shared via `FileProvider`.

6. **Install/version hygiene**
   - Previous mistake: an old backup project was built as v0.2.3 while the installed app was v0.4.1, causing Android downgrade rejection.
   - This repo should be the single source of truth for the Android client.

7. **Backend dependency**
   - STT, correction, semantic summary, and recording storage live outside this repo.
   - Android currently expects server endpoints under `vocanote.serverUrl` and uses `X-Upload-Token`.

## Known backlog

- Finalized AAC validation checks container metadata, audio track/sample presence, stability, and duration. A bounded codec-level decode probe remains backlog work.
- Destructive trim/split is disabled.
