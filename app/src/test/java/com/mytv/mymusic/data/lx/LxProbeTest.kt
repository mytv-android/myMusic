package com.mytv.mymusic.data.lx

import com.mytv.mymusic.data.native.PlayableStream
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LxProbeTest {
    private val server = MockWebServer()

    @Before fun setUp() { server.start() }
    @After fun tearDown() { server.close() }

    @Test
    fun readsRealMediaWithRange() = runBlocking {
        server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Type", "audio/mpeg").body("ID3test").build())
        val result = LxSources.probeStream(PlayableStream(url = server.url("/song.mp3").toString()))
        assertEquals("HTTP 206 · audio/mpeg", result)
        assertEquals("bytes=0-2047", server.takeRequest().headers["Range"])
    }

    @Test
    fun rejectsDeadUrl() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        try {
            LxSources.probeStream(PlayableStream(url = server.url("/dead.mp3").toString()))
            fail("404 must not pass the media test")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("404"))
        }
    }

    @Test
    fun rejectsHtmlErrorPage() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>expired</html>").build())
        try {
            LxSources.probeStream(PlayableStream(url = server.url("/expired.mp3").toString()))
            fail("HTML must not pass the media test")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("不是音频"))
        }
    }
}
