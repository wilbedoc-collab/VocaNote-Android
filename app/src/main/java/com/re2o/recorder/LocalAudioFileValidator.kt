package com.re2o.recorder

import java.io.File
import java.io.FileInputStream

/** Bounded header validation for local audio recovery; never decodes or materializes the file. */
object LocalAudioFileValidator {
    private const val HEADER_BYTES = 64

    fun isRecognizedAudio(file: File): Boolean {
        if (!file.isFile || file.length() <= 2048L) return false
        val header = ByteArray(HEADER_BYTES)
        val read = try {
            FileInputStream(file).use { it.read(header) }
        } catch (_: Exception) {
            return false
        }
        if (read < 4) return false
        val extension = file.extension.lowercase()
        return when (extension) {
            "m4a", "mp4" -> read >= 8 && header.copyOfRange(4, 8).contentEquals(byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
            "mp3" -> header.copyOfRange(0, 3).contentEquals(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())) ||
                ((header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xe0) == 0xe0)
            "wav" -> read >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
            "aac" -> (header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xf6) == 0xf0
            "ogg" -> String(header, 0, 4, Charsets.US_ASCII) == "OggS"
            "webm" -> (header[0].toInt() and 0xff) == 0x1a && (header[1].toInt() and 0xff) == 0x45 &&
                (header[2].toInt() and 0xff) == 0xdf && (header[3].toInt() and 0xff) == 0xa3
            else -> false
        }
    }
}
