package com.mytv.mymusic

import com.mytv.mymusic.data.update.UpdateManifest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesReleaseManifest() {
        val raw = """
            {
              "schema": "mymusic.update",
              "channel": "stable",
              "versionCode": 129,
              "versionName": "1.0.0.129",
              "tag": "v1.0.0.129",
              "publishedAt": "2026-10-20T08:00:00Z",
              "minSdk": 23,
              "apk": {
                "url": "https://github.com/mytv-android/myMusic/releases/download/v1.0.0.129/myMusic-1.0.0.129.apk",
                "size": 19293798,
                "sha256": "8b0f9c4e"
              },
              "notes": ["自建歌单可以同步到 Gitee 代码片段。", "修复换源播放中断后进度没有恢复的问题。"]
            }
        """.trimIndent()

        val manifest = json.decodeFromString<UpdateManifest>(raw)

        assertEquals("mymusic.update", manifest.schema)
        assertEquals(129L, manifest.versionCode)
        assertEquals("1.0.0.129", manifest.versionName)
        assertEquals(23, manifest.minSdk)
        assertEquals(19293798L, manifest.apk.size)
        assertEquals("8b0f9c4e", manifest.apk.sha256)
        assertEquals(2, manifest.notes.size)
        assertTrue(manifest.notes.first().contains("Gitee"))
    }

    @Test
    fun ignoresUnknownFieldsForForwardCompatibility() {
        val raw = """
            {
              "schema": "mymusic.update",
              "channel": "stable",
              "versionCode": 130,
              "versionName": "1.0.0.130",
              "futureField": {"nested": true},
              "apk": {"url": "https://example.invalid/app.apk", "size": 1, "sha256": "abc"}
            }
        """.trimIndent()

        val manifest = json.decodeFromString<UpdateManifest>(raw)

        assertEquals(130L, manifest.versionCode)
        assertEquals("https://example.invalid/app.apk", manifest.apk.url)
    }
}
