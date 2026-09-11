package com.re2o.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import com.sun.net.httpserver.HttpServer

class StreamingMultipartUploadTest {
    private class ProbeConnection : HttpURLConnection(URL("http://localhost")) {
        var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        fun configuredFixedLength(): Long = fixedContentLengthLong
    }

    private class DiscardingProbeOutputStream : OutputStream() {
        var totalBytes = 0L
        var maxWriteSize = 0
        override fun write(value: Int) {
            totalBytes += 1
            maxWriteSize = maxOf(maxWriteSize, 1)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            totalBytes += length
            maxWriteSize = maxOf(maxWriteSize, length)
        }
    }

    @Test fun configuresFixedLengthHttpStreaming() {
        val connection = ProbeConnection()
        StreamingMultipartUpload.configureConnection(connection, 123456789L)
        assertEquals(123456789L, connection.configuredFixedLength())
    }

    @Test fun streamsLargeSyntheticFilesWithOneBoundedBuffer() {
        val sizes = listOf(100L, 500L, 1024L).map { it * 1024L * 1024L }
        sizes.forEach { size ->
            val file = File.createTempFile("vocanote-stream-", ".m4a")
            try {
                RandomAccessFile(file, "rw").use { it.setLength(size) }
                val sink = DiscardingProbeOutputStream()
                StreamingMultipartUpload.writeBody(
                    output = sink,
                    boundary = "phase88a6",
                    fields = listOf("client_recording_id" to "test-$size"),
                    file = file
                )
                assertTrue(sink.totalBytes > size)
                assertTrue(sink.maxWriteSize <= StreamingMultipartUpload.BUFFER_SIZE)
            } finally {
                file.delete()
            }
        }
    }

    @Test fun actualHttpConnectionUsesFixedLengthStreaming() {
        var contentLength = -1L
        var received = 0L
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/upload") { exchange ->
            contentLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: -1L
            val buffer = ByteArray(8 * 1024)
            exchange.requestBody.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    received += count
                }
            }
            val response = "{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val file = File.createTempFile("vocanote-http-", ".m4a")
        try {
            RandomAccessFile(file, "rw").use { it.setLength(8L * 1024L * 1024L) }
            val connection = URL("http://127.0.0.1:${server.address.port}/upload").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=phase88a6")
            val fields = listOf("client_recording_id" to "one")
            val expectedLength = StreamingMultipartUpload.contentLength("phase88a6", fields, file)
            StreamingMultipartUpload.configureConnection(connection, expectedLength)
            connection.outputStream.use {
                StreamingMultipartUpload.writeBody(it, "phase88a6", fields, file)
            }
            assertEquals(200, connection.responseCode)
            assertEquals("{\"ok\":true}", connection.inputStream.use { StreamingMultipartUpload.readResponse(it) })
            assertEquals(expectedLength, contentLength)
            assertEquals(expectedLength, received)
            assertTrue(received > file.length())
            connection.disconnect()
        } finally {
            file.delete()
            server.stop(0)
        }
    }

    @Test fun interruptedWriteLeavesSourceIntactAndRetryable() {
        val file = File.createTempFile("vocanote-interrupt-", ".m4a")
        try {
            RandomAccessFile(file, "rw").use { it.setLength(16L * 1024L * 1024L) }
            val expectedLength = file.length()
            val failing = object : OutputStream() {
                var written = 0L
                override fun write(value: Int) {
                    if (written >= 1024L * 1024L) throw java.io.IOException("simulated disconnect")
                    written++
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (written + length > 1024L * 1024L) throw java.io.IOException("simulated disconnect")
                    written += length
                }
            }
            var interrupted = false
            try {
                StreamingMultipartUpload.writeBody(failing, "phase88a6", emptyList(), file)
            } catch (_: java.io.IOException) {
                interrupted = true
            }
            assertTrue(interrupted)
            assertTrue(file.exists())
            assertEquals(expectedLength, file.length())

            val retry = DiscardingProbeOutputStream()
            StreamingMultipartUpload.writeBody(retry, "phase88a6", emptyList(), file)
            assertTrue(retry.totalBytes > expectedLength)
            assertEquals(expectedLength, file.length())
        } finally {
            file.delete()
        }
    }

    @Test fun cancellationPredicateStopsStreamingBeforeCompleteBody() {
        val file = File.createTempFile("vocanote-cancel-", ".m4a")
        try {
            RandomAccessFile(file, "rw").use { it.setLength(16L * 1024L * 1024L) }
            val sink = DiscardingProbeOutputStream()
            var checks = 0
            var cancelled = false
            try {
                StreamingMultipartUpload.writeBody(
                    output = sink,
                    boundary = "phase88a6",
                    fields = listOf("client_recording_id" to "cancelled"),
                    file = file,
                    shouldContinue = { ++checks < 24 }
                )
            } catch (_: UploadCancelledException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertTrue(sink.totalBytes < file.length())
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test fun progressUsesExactMultipartTotalIncludingFinalBoundary() {
        val file = File.createTempFile("vocanote-progress-", ".m4a")
        try {
            file.writeBytes(ByteArray(4096) { it.toByte() })
            val boundary = "phase88a6-progress"
            val fields = listOf("title" to "한글 제목", "client_recording_id" to "progress-one")
            val expected = StreamingMultipartUpload.contentLength(boundary, fields, file)
            val reports = mutableListOf<Pair<Long, Long>>()
            val sink = DiscardingProbeOutputStream()
            StreamingMultipartUpload.writeBody(
                output = sink,
                boundary = boundary,
                fields = fields,
                file = file,
                onProgress = { sent, total -> reports += sent to total }
            )
            assertTrue(reports.isNotEmpty())
            assertTrue(reports.all { it.second == expected })
            assertEquals(expected, reports.last().first)
            assertEquals(expected, sink.totalBytes)
        } finally {
            file.delete()
        }
    }

    @Test fun sameLengthMutationDuringStreamingIsRejected() {
        val file = File.createTempFile("vocanote-mutation-", ".m4a")
        try {
            file.writeBytes(ByteArray(4 * StreamingMultipartUpload.BUFFER_SIZE) { (it % 251).toByte() })
            var mutated = false
            val sink = object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (!mutated && length == StreamingMultipartUpload.BUFFER_SIZE) {
                        RandomAccessFile(file, "rw").use {
                            it.seek(0L)
                            val original = it.read()
                            it.seek(0L)
                            it.write(original xor 0xff)
                        }
                        mutated = true
                    }
                }
            }
            var rejected = false
            try {
                StreamingMultipartUpload.writeBody(sink, "phase88a6", emptyList(), file)
            } catch (e: java.io.IOException) {
                rejected = e.message?.contains("changed") == true
            }
            assertTrue(mutated)
            assertTrue(rejected)
            assertEquals((4 * StreamingMultipartUpload.BUFFER_SIZE).toLong(), file.length())
        } finally {
            file.delete()
        }
    }

    @Test fun responseReadingIsBounded() {
        val oversized = ByteArray(StreamingMultipartUpload.MAX_RESPONSE_BYTES + 1) { 'a'.code.toByte() }
        var threw = false
        try {
            StreamingMultipartUpload.readResponse(oversized.inputStream())
        } catch (_: java.io.IOException) {
            threw = true
        }
        assertTrue(threw)
    }
}
