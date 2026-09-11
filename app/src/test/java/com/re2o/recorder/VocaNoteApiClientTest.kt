package com.re2o.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class VocaNoteApiClientTest {
    private class DisconnectTrackingConnection : HttpURLConnection(URL("http://localhost")) {
        var disconnected = false

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }

    private class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test fun boundedResponseReadReturnsBodyAndClosesStream() {
        val stream = CloseTrackingInputStream("okay".toByteArray())

        assertEquals("okay", VocaNoteApiClient.readResponseBounded(stream, 4))
        assertTrue(stream.closed)
    }

    @Test fun boundedResponseReadRejectsOversizedBodyAndClosesStream() {
        val stream = CloseTrackingInputStream("oversized".toByteArray())

        try {
            VocaNoteApiClient.readResponseBounded(stream, 4)
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
            assertTrue(stream.closed)
        }
    }

    @Test fun connectionIsDisconnectedAfterSuccessAndFailure() {
        val successful = DisconnectTrackingConnection()
        assertEquals("done", VocaNoteApiClient.useConnection(successful) { "done" })
        assertTrue(successful.disconnected)

        val failed = DisconnectTrackingConnection()
        try {
            VocaNoteApiClient.useConnection(failed) { throw IOException("boom") }
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
            assertTrue(failed.disconnected)
        }
    }

    @Test fun purgeCommandRejectsNonPositiveGeneration() {
        listOf(0, -1).forEach { generation ->
            try {
                VocaNoteApiClient.PurgeCommand("purge", "recording", generation)
                throw AssertionError("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // Invalid commands cannot reach mapping, deletion, or ACK code.
            }
        }
    }
}