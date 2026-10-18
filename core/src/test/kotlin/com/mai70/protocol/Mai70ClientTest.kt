package com.mai70.protocol

import kotlinx.coroutines.runBlocking
import com.mai70.protocol.http.HttpTransport
import com.mai70.protocol.models.Category
import com.mai70.protocol.models.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class MockHttpTransport : HttpTransport {
    val requestedUrls = mutableListOf<String>()
    val responses = mutableMapOf<String, String>()
    val streams = mutableMapOf<String, InputStream>()

    override suspend fun get(url: String): String? {
        requestedUrls.add(url)
        for ((pattern, response) in responses) {
            if (url.contains(pattern)) return response
        }
        return null
    }

    override suspend fun openStream(url: String): InputStream? {
        requestedUrls.add(url)
        for ((pattern, stream) in streams) {
            if (url.contains(pattern)) return stream
        }
        return null
    }

    override suspend fun probe(url: String): Pair<Long, String?>? {
        requestedUrls.add(url)
        return Pair(123456L, "Mon, 01 Jan 2024 12:00:00 GMT")
    }
}

class Mai70ClientTest {

    @Test
    fun testPairingFlow() = runBlocking {
        val transport = MockHttpTransport()
        transport.responses["BindByBanya.cgi"] = "{\"ResultCode\":0, \"Token\":\"sess_12345\", \"timestamp\":\"1700000000\"}"
        transport.responses["UserconfirmByBanya.cgi"] = "{\"ResultCode\":0, \"Token\":\"sess_12345\"}"
        transport.responses["client.cgi"] = "{\"ResultCode\":0}"

        val client = Mai70Client(transport = transport, host = "192.168.0.1", port = 80)
        var waitingCalled = false
        val token = client.pair(onWaitingForButton = { waitingCalled = true }, maxAttempts = 2, delayMs = 10)

        assertEquals("sess_12345", token)
        assertTrue(waitingCalled)
    }

    @Test
    fun testListFilesPaginationAndFallbacks() = runBlocking {
        val transport = MockHttpTransport()
        transport.responses["client.cgi"] = "{\"ResultCode\":0}"
        transport.responses["setaccessalbum.cgi"] = "{\"ResultCode\":0}"
        transport.responses["getfilelist.cgi"] = """
            {
                "ResultCode": 0,
                "Result": [
                    {"path": "Normal", "name": "FILE1.MP4", "size": "1000"},
                    {"path": "Normal", "name": "FILE2.MP4", "size": "2000"}
                ]
            }
        """.trimIndent()

        val client = Mai70Client(transport = transport)
        val files = client.listFiles(Category.NORMAL)

        assertEquals(2, files.size)
        assertEquals("FILE1.MP4", files[0].name)
        assertEquals("FILE2.MP4", files[1].name)
    }

    @Test
    fun testDeleteRemoteFile() = runBlocking {
        val transport = MockHttpTransport()
        transport.responses["client.cgi"] = "{\"ResultCode\":0}"
        transport.responses["setaccessalbum.cgi"] = "{\"ResultCode\":0}"
        transport.responses["delete.cgi"] = "{\"ResultCode\":0}"

        val client = Mai70Client(transport = transport)
        val entry = FileEntry(path = "Normal", name = "TEST.MP4", size = 1000L, category = Category.NORMAL)
        client.delete(entry)

        assertTrue(transport.requestedUrls.any { it.contains("delete.cgi") })
    }

    @Test
    fun testOpenFileStream() = runBlocking {
        val transport = MockHttpTransport()
        transport.responses["client.cgi"] = "{\"ResultCode\":0}"
        transport.responses["setaccessalbum.cgi"] = "{\"ResultCode\":0}"
        transport.streams["FILE.MP4"] = ByteArrayInputStream("test content".toByteArray())

        val client = Mai70Client(transport = transport)
        val entry = FileEntry(path = "Normal", name = "FILE.MP4", size = 12L, category = Category.NORMAL)
        val stream = client.openFile(entry)

        assertNotNull(stream)
        assertEquals("test content", stream.bufferedReader().use { it.readText() })
    }
}
