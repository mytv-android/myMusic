package com.mytv.mymusic.data.lx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LxScriptHeadersTest {
    @Test
    fun parsesOfficialHeader() {
        val metadata = LxScriptHeaders.parse(
            """
            /*!
             * @name 𝖧ello World
             * @author hello world
             * @version 260925
             * @description 每天都要开心哦
             * @homepage https://example.com/source
             */
            const source = globalThis.lx
            """.trimIndent(),
        )
        assertEquals("𝖧ello World", metadata.name)
        assertEquals("hello world", metadata.author)
        assertEquals("260925", metadata.version)
        assertEquals("每天都要开心哦", metadata.description)
        assertEquals("https://example.com/source", metadata.homepage)
    }

    @Test
    fun rejectsScriptWithoutName() {
        try {
            LxScriptHeaders.parse("const source = globalThis.lx")
            fail("missing @name must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("@name"))
        }
    }

    @Test
    fun hashesScriptContentForAtomicStorage() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "abc".sha256())
    }

    @Test
    fun rejectsOversizedScript() {
        val script = "/** @name too-large */\n" + " ".repeat(MAX_SCRIPT_BYTES)
        try {
            LxScriptHeaders.parse(script)
            fail("oversized script must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("2 MB"))
        }
    }
}
