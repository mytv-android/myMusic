package com.mytv.mymusic

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {
    private val environment = CrashEnvironment(
        versionName = "1.1",
        versionCode = 42,
        androidRelease = "15",
        api = 35,
        manufacturer = "Google",
        model = "Pixel 8",
        device = "shiba",
        abi = "arm64-v8a",
    )

    @Test
    fun `report redacts credentials and keeps the complete stack`() {
        val stack = buildString {
            appendLine("java.lang.IllegalStateException: token=stack-secret")
            for (index in 0 until 300) {
                appendLine("\tat com.mytv.mymusic.Test.step$index(Test.kt:$index)")
            }
            appendLine("Caused by: java.io.IOException: playback failed")
        }
        val logs = listOf(
            "Cookie: session=cookie-secret; theme=dark",
            "request {\"access_token\": \"json-secret\"}",
            "https://user:password@example.invalid/dav?refresh_token=query-secret&x=1",
            "ordinary playback message",
        )

        val report = CrashReportText.buildReport(
            environment = environment,
            crashedAt = 0,
            threadName = "main",
            threadId = 2,
            stackTrace = stack,
            recentLogs = logs,
        )

        assertTrue(report.contains("myMusic 1.1 (42) · Android 15 (API 35) · Google Pixel 8 (shiba) · ABI arm64-v8a"))
        assertTrue(report.contains("Thread: main (id=2)"))
        assertTrue(report.contains("at com.mytv.mymusic.Test.step299(Test.kt:299)"))
        assertTrue(report.contains("Caused by: java.io.IOException: playback failed"))
        assertTrue(report.contains("Recent AppLog (4 entries):"))
        assertTrue(report.contains("ordinary playback message"))
        assertTrue(report.contains("Cookie: <redacted>"))
        assertTrue(report.lineSequence().filter { "access_token" in it || "json-secret" in it }.joinToString("\n"), report.contains("\"access_token\": \"<redacted>\""))
        assertTrue(report.contains("https://<redacted>@example.invalid/dav?refresh_token=<redacted>&x=1"))
        listOf("stack-secret", "cookie-secret", "json-secret", "query-secret", "user:password").forEach { secret ->
            assertFalse("报告仍含敏感值 $secret", report.contains(secret))
        }
        assertTrue(report.contains("native crashes do not invoke this handler"))
    }

    @Test
    fun `environment summary is single line and redacted`() {
        val summary = environment.copy(manufacturer = "Acme\ntoken=device-secret").summary()

        assertFalse(summary.contains('\n'))
        assertFalse(summary.contains("device-secret"))
        assertTrue(summary.contains("token=<redacted>"))
    }

    @Test
    fun `stored report summary comes from its environment line`() {
        val report = CrashReportText.buildReport(
            environment = environment,
            crashedAt = 1_700_000_000_000,
            threadName = "worker",
            threadId = 7,
            stackTrace = "java.lang.RuntimeException\n",
            recentLogs = emptyList(),
        )

        assertEquals(environment.summary(), CrashReportText.summaryFromReport(report))
        assertTrue(report.contains("Captured: 2023-11-14T22:13:20Z"))
        assertTrue(report.contains("Recent AppLog (0 entries):\n(none)"))
    }

    @Test
    fun `issue url contains only short environment and paste instructions`() {
        val url = CrashReportText.issueUrl(environment.summary())
        val decoded = URLDecoder.decode(url, Charsets.UTF_8.name())

        assertTrue(url.startsWith("https://github.com/mytv-android/myMusic/issues/new?title="))
        assertTrue(decoded.contains(environment.summary()))
        assertTrue(decoded.contains("请说明操作步骤、实际结果和能否复现"))
        assertTrue(decoded.contains("复制完整报告并粘贴到这里"))
        assertTrue(decoded.contains("链接不含堆栈，也不会自动上传"))
        assertFalse(decoded.contains("Recent AppLog"))
        assertFalse(decoded.contains("Exception:"))
    }

    @Test
    fun `issue url remains bounded for unusually long unicode device fields`() {
        val url = CrashReportText.issueUrl("设备".repeat(500))

        assertTrue("Issue URL 过长：${url.length}", url.length <= 2048)
        assertFalse(url.contains("设备".repeat(500)))
    }
}
