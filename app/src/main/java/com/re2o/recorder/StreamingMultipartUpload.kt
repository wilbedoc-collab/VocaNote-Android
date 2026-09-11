package com.re2o.recorder

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.security.MessageDigest

class UploadCancelledException : IOException("upload cancelled")

/** Writes multipart uploads with memory bounded independently of the file size. */
object StreamingMultipartUpload {
    const val BUFFER_SIZE = 64 * 1024
    const val MAX_RESPONSE_BYTES = 1024 * 1024

    fun configureConnection(connection: HttpURLConnection, contentLength: Long) {
        require(contentLength >= 0L) { "negative multipart content length" }
        connection.setFixedLengthStreamingMode(contentLength)
    }

    fun contentLength(
        boundary: String,
        fields: List<Pair<String, String>>,
        file: File,
        fileFieldName: String = "audio",
        contentType: String = "audio/mp4"
    ): Long {
        var total = 0L
        fun addText(text: String) {
            total = Math.addExact(total, text.toByteArray(Charsets.UTF_8).size.toLong())
        }
        fields.forEach { (name, value) ->
            addText("--$boundary\r\n")
            addText("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            addText(value)
            addText("\r\n")
        }
        addText("--$boundary\r\n")
        addText("Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"${file.name}\"\r\n")
        addText("Content-Type: $contentType\r\n\r\n")
        total = Math.addExact(total, file.length())
        addText("\r\n--$boundary--\r\n")
        return total
    }

    fun writeBody(
        output: OutputStream,
        boundary: String,
        fields: List<Pair<String, String>>,
        file: File,
        fileFieldName: String = "audio",
        contentType: String = "audio/mp4",
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        shouldContinue: () -> Boolean = { true }
    ) {
        var sent = 0L
        val expectedFileLength = file.length()
        val exactTotal = contentLength(boundary, fields, file, fileFieldName, contentType)
        var fileBytesSent = 0L
        val uploadDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)

        fun writeBytes(bytes: ByteArray) {
            if (!shouldContinue()) throw UploadCancelledException()
            output.write(bytes)
            sent += bytes.size
            onProgress(sent, exactTotal)
        }
        fun writeText(text: String) = writeBytes(text.toByteArray(Charsets.UTF_8))

        fields.forEach { (name, value) ->
            writeText("--$boundary\r\n")
            writeText("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            writeText(value)
            writeText("\r\n")
        }
        writeText("--$boundary\r\n")
        writeText("Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"${file.name}\"\r\n")
        writeText("Content-Type: $contentType\r\n\r\n")
        FileInputStream(file).use { input ->
            while (true) {
                if (!shouldContinue()) throw UploadCancelledException()
                val read = input.read(buffer)
                if (read <= 0) break
                if (!shouldContinue()) throw UploadCancelledException()
                uploadDigest.update(buffer, 0, read)
                output.write(buffer, 0, read)
                fileBytesSent += read
                sent += read
                onProgress(sent, exactTotal)
            }
        }
        if (fileBytesSent != expectedFileLength) throw IOException("audio file changed during upload")
        val verificationDigest = MessageDigest.getInstance("SHA-256")
        var verifiedBytes = 0L
        FileInputStream(file).use { input ->
            while (true) {
                if (!shouldContinue()) throw UploadCancelledException()
                val read = input.read(buffer)
                if (read <= 0) break
                verificationDigest.update(buffer, 0, read)
                verifiedBytes += read
            }
        }
        if (verifiedBytes != expectedFileLength ||
            !uploadDigest.digest().contentEquals(verificationDigest.digest())
        ) {
            throw IOException("audio file changed during upload")
        }
        if (!shouldContinue()) throw UploadCancelledException()
        writeText("\r\n--$boundary--\r\n")
        output.flush()
    }

    @Throws(IOException::class)
    fun readResponse(input: InputStream): String {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw IOException("response body exceeds $MAX_RESPONSE_BYTES bytes")
            result.write(buffer, 0, read)
        }
        return result.toString(Charsets.UTF_8.name())
    }
}
