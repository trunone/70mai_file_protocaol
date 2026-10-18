package com.github.trunone.mai70protocol

import com.github.trunone.mai70protocol.models.Authority
import com.github.trunone.mai70protocol.models.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mai70ProtocolTest {

    @Test
    fun testCalculateMD5() {
        val input = "123456" + Mai70Protocol.PAIRING_SALT
        val hash = Mai70Protocol.calculateMD5(input)
        assertNotNull(hash)
        assertEquals(32, hash.length)
    }

    @Test
    fun testGenerateSignKey() {
        val query = "client.cgi?&-operation=register&-ip=192.168.0.2&-timestamp=1700000000"
        val token = "testtoken123"
        val expected = Mai70Protocol.calculateMD5(query + token)
        val actual = Mai70Protocol.generateSignKey(query, token)
        assertEquals(expected, actual)
    }

    @Test
    fun testBuildCgiUrl() {
        val authority = Authority(host = "192.168.0.1", port = 80, token = "mytoken")
        val url = Mai70Protocol.buildCgiUrl(authority, "client.cgi?", "&-operation=register")
        assertTrue(url.startsWith("http://192.168.0.1:80/cgi-bin/client.cgi?&-operation=register&-signkey="))
    }

    @Test
    fun testSanitizeJsonResponse() {
        val messyJson = "{\"Result\": [{\"name\": \"test.mp4\"},\n]}"
        val sanitized = Mai70Protocol.sanitizeJsonResponse(messyJson)
        assertFalse(sanitized.contains(",\n]}"))
    }

    @Test
    fun testExtractJsonValue() {
        val json = "{\"ResultCode\":0, \"Token\":\"abc123token\", \"timestamp\":\"1700000000\"}"
        assertEquals("0", Mai70Protocol.extractJsonValue(json, "ResultCode"))
        assertEquals("abc123token", Mai70Protocol.extractJsonValue(json, "Token"))
        assertEquals("1700000000", Mai70Protocol.extractJsonValue(json, "timestamp"))
    }

    @Test
    fun testIsSuccessResponse() {
        assertTrue(Mai70Protocol.isSuccessResponse("{\"ResultCode\":0}"))
        assertTrue(Mai70Protocol.isSuccessResponse("{\"ResultCode\":\"0\"}"))
        assertTrue(Mai70Protocol.isSuccessResponse("success"))
        assertFalse(Mai70Protocol.isSuccessResponse("{\"ResultCode\":-1}"))
    }

    @Test
    fun testParseFileCountsResponse() {
        val json = """
            {
                "ResultCode": 0,
                "Result": [
                    {"type": "0", "count": "12"},
                    {"type": "1", "count": "3"}
                ]
            }
        """.trimIndent()

        val counts = Mai70Protocol.parseFileCountsResponse(json)
        assertEquals(12, counts[Category.NORMAL])
        assertEquals(3, counts[Category.EVENT])
    }

    @Test
    fun testParseFileListResponse() {
        val json = """
            {
                "ResultCode": "0",
                "Result": [
                    {"path": "Normal", "name": "NO20240101-120000.MP4", "size": "104857600"},
                    {"path": "Normal", "name": "NO20240101-120100.MP4", "size": "104857600"}
                ]
            }
        """.trimIndent()

        val entries = Mai70Protocol.parseFileListResponse(json, Category.NORMAL)
        assertNotNull(entries)
        assertEquals(2, entries!!.size)
        assertEquals("NO20240101-120000.MP4", entries[0].name)
        assertEquals(104857600L, entries[0].size)
        assertEquals(Category.NORMAL, entries[0].category)
    }
}
