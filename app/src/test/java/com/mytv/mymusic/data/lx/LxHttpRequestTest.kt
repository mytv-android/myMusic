package com.mytv.mymusic.data.lx

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LxHttpRequestTest {
    @Test
    fun stringifiedJsonBodyIsSentVerbatim() {
        val raw = """{"a":1}"""
        val request = buildLxRequest(
            "https://example.com/api",
            buildJsonObject {
                put("method", "POST")
                put("headers", buildJsonObject { put("Content-Type", "application/json") })
                put("body", JsonPrimitive(raw))
            },
        )
        assertEquals(raw, request.body.readText())
    }

    @Test
    fun structuredJsonBodyIsEncodedOnce() {
        val request = buildLxRequest(
            "https://example.com/api",
            buildJsonObject {
                put("method", "POST")
                put("headers", buildJsonObject { put("Content-Type", "application/json") })
                put("body", buildJsonObject { put("a", 1) })
            },
        )
        assertEquals("""{"a":1}""", request.body.readText())
    }

    @Test
    fun formBodyIsUrlEncoded() {
        val request = buildLxRequest(
            "https://example.com/api",
            buildJsonObject {
                put("method", "POST")
                put("form", buildJsonObject { put("key", "a b"); put("lang", "中文") })
            },
        )
        assertEquals("application/x-www-form-urlencoded", request.header("Content-Type"))
        assertEquals("key=a+b&lang=%E4%B8%AD%E6%96%87", request.body.readText())
    }

    @Test
    fun getDoesNotInventRequestBody() {
        val request = buildLxRequest(
            "https://example.com/api",
            buildJsonObject { put("body", JsonPrimitive("ignored")) },
        )
        assertNull(request.body)
    }

    private fun okhttp3.RequestBody?.readText(): String {
        check(this != null)
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }
}
