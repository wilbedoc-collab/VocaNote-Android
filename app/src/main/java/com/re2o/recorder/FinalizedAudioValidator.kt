package com.re2o.recorder

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

/** Requires both a recognized container header and Android-readable audio media. */
object FinalizedAudioValidator {
    fun isValid(file: File): Boolean = isValid(file, ::hasPlayableAudio)

    internal fun isValid(file: File, playableProbe: (File) -> Boolean): Boolean {
        if (!LocalAudioFileValidator.isRecognizedAudio(file)) return false
        return try {
            playableProbe(file)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasPlayableAudio(file: File): Boolean {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        return try {
            extractor.setDataSource(file.absolutePath)
            var audioTrack = -1
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    break
                }
            }
            if (audioTrack < 0) return false
            extractor.selectTrack(audioTrack)
            if (extractor.sampleTime < 0L || extractor.sampleSize <= 0L) return false

            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return false
            durationMs > 0L
        } catch (_: Exception) {
            false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
