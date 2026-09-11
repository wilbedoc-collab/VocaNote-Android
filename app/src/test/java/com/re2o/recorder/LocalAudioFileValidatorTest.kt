package com.re2o.recorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class LocalAudioFileValidatorTest {
    private fun fileWithHeader(suffix: String, header: ByteArray): File {
        val file = File.createTempFile("vocanote-audio-", suffix)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(4096L)
            raf.seek(0L)
            raf.write(header)
        }
        return file
    }

    @Test fun validM4aHeaderIsAccepted() {
        val file = fileWithHeader(".m4a", byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
        try {
            assertTrue(LocalAudioFileValidator.isRecognizedAudio(file))
        } finally {
            file.delete()
        }
    }

    @Test fun largeMalformedM4aIsRejected() {
        val file = fileWithHeader(".m4a", ByteArray(64) { 0x55 })
        try {
            assertFalse(LocalAudioFileValidator.isRecognizedAudio(file))
        } finally {
            file.delete()
        }
    }

    @Test fun unsupportedExtensionAndTinyFileAreRejected() {
        val unsupported = fileWithHeader(".bin", byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
        val tiny = File.createTempFile("vocanote-audio-", ".m4a").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        try {
            assertFalse(LocalAudioFileValidator.isRecognizedAudio(unsupported))
            assertFalse(LocalAudioFileValidator.isRecognizedAudio(tiny))
        } finally {
            unsupported.delete()
            tiny.delete()
        }
    }
}
