package com.github.trunone.mai70protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Mai70AuthTest {

    @Test
    fun testBindByBanyaFallbackCandidates() = runBlocking {
        val transport = MockHttpTransport()
        // Simulate response matching one of the alternative candidate query formats
        transport.responses["BindByBanya.cgi"] = "{\"ResultCode\":0, \"Token\":\"token_fallback_123\", \"timestamp\":\"1700000000\"}"
        transport.responses["UserconfirmByBanya.cgi"] = "{\"ResultCode\":0, \"Token\":\"token_fallback_123\"}"
        transport.responses["client.cgi"] = "{\"ResultCode\":0}"

        val auth = Mai70Auth(transport = transport, host = "192.168.0.1", port = 80)
        val token = auth.pair(maxAttempts = 1)

        assertEquals("token_fallback_123", token)
        assertNotNull(transport.requestedUrls.find { it.contains("BindByBanya.cgi") })
    }
}
