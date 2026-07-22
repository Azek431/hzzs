package top.azek431.hzzs.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppLogLevel
import top.azek431.hzzs.core.model.DeveloperConfig
import top.azek431.hzzs.core.model.OverlayBlockReason
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.core.preferences.ConfigJson

class AppLogTest {
    @Before
    fun setUp() {
        AppLog.clear()
        AppLog.configure(enabled = true, level = AppLogLevel.VERBOSE)
    }

    @Test
    fun redactMasksBearerAndTokenKeyValues() {
        val masked = AppLog.redact("Authorization: Bearer abcdef.ghij-klmn and token=supersecret")
        assertFalse(masked.contains("abcdef"))
        assertFalse(masked.contains("supersecret"))
        assertTrue(masked.contains("Bearer <redacted>"))
        assertTrue(masked.contains("<redacted>"))
    }

    @Test
    fun ringBufferRespectsCapacityAndOrder() {
        AppLog.configure(enabled = true, level = AppLogLevel.INFO)
        repeat(5) { index -> AppLog.i("test", "msg-$index") }
        val snap = AppLog.snapshot()
        assertEquals(5, snap.size)
        assertEquals("msg-0", snap.first().message)
        assertEquals("msg-4", snap.last().message)
    }

    @Test
    fun debugSuppressedWhenDeveloperDisabled() {
        AppLog.configure(enabled = false, level = AppLogLevel.VERBOSE)
        AppLog.d("test", "hidden-debug")
        AppLog.i("test", "visible-info")
        val messages = AppLog.snapshot().map { it.message }
        assertFalse(messages.contains("hidden-debug"))
        assertTrue(messages.contains("visible-info"))
    }

    @Test
    fun minLevelFiltersLowerSeverities() {
        AppLog.configure(enabled = true, level = AppLogLevel.WARN)
        AppLog.i("test", "info-drop")
        AppLog.w("test", "warn-keep")
        val messages = AppLog.snapshot().map { it.message }
        assertFalse(messages.contains("info-drop"))
        assertTrue(messages.contains("warn-keep"))
    }

    @Test
    fun queryFiltersByTagAndTextAndSupportsNewestFirst() {
        AppLog.configure(enabled = true, level = AppLogLevel.VERBOSE)
        AppLog.i("vision", "frame ok")
        AppLog.e("algorithm", "activate failed for pack.demo")
        AppLog.w("vision", "capture slow")
        val onlyAlgo = AppLog.query(tagEquals = "algorithm")
        assertEquals(1, onlyAlgo.size)
        assertTrue(onlyAlgo.single().message.contains("activate failed"))
        val search = AppLog.query(query = "capture")
        assertEquals(1, search.size)
        val newest = AppLog.query(newestFirst = true)
        assertTrue(newest.first().message.contains("capture slow") || newest.first().message.contains("activate") || newest.first().message.contains("frame"))
        assertEquals(AppLog.size().toLong().coerceAtLeast(1L) > 0, AppLog.revision() > 0)
        assertTrue(AppLog.knownTags().contains("vision"))
        assertTrue(AppLog.formatText(tagEquals = "algorithm").contains("activate failed"))
    }

    @Test
    fun clearBumpsRevision() {
        AppLog.i("app", "before-clear")
        val before = AppLog.revision()
        AppLog.clear()
        assertEquals(0, AppLog.size())
        assertTrue(AppLog.revision() > before)
    }
}

class DiagnosticsExporterTest {
    @Test
    fun reportOmitsBearerAndIncludesSummary() {
        AppLog.clear()
        AppLog.configure(enabled = true, level = AppLogLevel.INFO)
        AppLog.i("vision", "Authorization: Bearer should-not-appear")
        val report = DiagnosticsExporter.buildReport(
            versionName = "0.1.0-test",
            versionCode = 1L,
            config = AppConfig(developer = DeveloperConfig(enabled = true, logLevel = AppLogLevel.DEBUG)),
            mcp = McpDiagnosticsSnapshot(running = true, port = 8765, lastError = null),
            debugFrameCount = 3,
            algorithm = AlgorithmDiagnosticsSnapshot(
                algorithmId = "builtin.hzzs.base",
                version = "0.1.0",
                generation = 3L,
                usingBuiltinFallback = true,
                loadError = null,
                nativeAvailable = false,
                pendingCatalogId = null,
                analysisRunning = false,
            ),
            runtime = RuntimeStatus(
                running = true,
                overlayVisible = false,
                overlayBlockReason = OverlayBlockReason.PERMISSION,
            ),
            logLimit = 50,
        )
        assertTrue(report.contains("versionName=0.1.0-test"))
        assertTrue(report.contains("mcp.port=8765"))
        assertTrue(report.contains("debugFrameCount=3"))
        assertTrue(report.contains("developer.logLevel=DEBUG"))
        assertTrue(report.contains("id=builtin.hzzs.base"))
        assertTrue(report.contains("version=0.1.0"))
        assertTrue(report.contains("generation=3"))
        assertTrue(report.contains("== Algorithm activation =="))
        assertTrue(report.contains("vision.overlayBlockReason=PERMISSION"))
        assertTrue(report.contains("capture.requested="))
        assertTrue(report.contains("capture.effective="))
        assertTrue(report.contains("capture.fallbackReason="))
        // 本地时区 + 偏移；不得再出现假 UTC 的 `...Z` 样式（无偏移）。
        assertTrue(report.contains("generatedAt="))
        assertTrue(
            Regex("""generatedAt=\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}""")
                .containsMatchIn(report),
        )
        assertTrue(report.contains("Timestamps use the device local timezone with offset"))
        assertFalse(report.contains("should-not-appear"))
        assertFalse(report.contains("Bearer should"))
        assertTrue(report.contains("Bearer <redacted>") || report.contains("<redacted>"))
    }
}

class DeveloperConfigJsonTest {
    @Test
    fun configJsonRoundTripKeepsLogLevelAndDeveloperFields() {
        val original = AppConfig(
            developer = DeveloperConfig(
                enabled = true,
                saveDebugFrames = true,
                showCoordinateGrid = true,
                frameRateLimit = 45,
                nativeBenchmarkIterations = 300,
                logLevel = AppLogLevel.DEBUG,
            ),
        )
        val decoded = ConfigJson.decode(ConfigJson.encode(original))
        assertEquals(true, decoded.developer.enabled)
        assertEquals(true, decoded.developer.saveDebugFrames)
        assertEquals(true, decoded.developer.showCoordinateGrid)
        assertEquals(45, decoded.developer.frameRateLimit)
        assertEquals(300, decoded.developer.nativeBenchmarkIterations)
        assertEquals(AppLogLevel.DEBUG, decoded.developer.logLevel)
    }

    @Test
    fun legacyDeveloperWithoutLogLevelDefaultsToInfo() {
        val legacy = """
            {
              "schemaVersion": 6,
              "developer": {
                "enabled": true,
                "saveDebugFrames": false,
                "showCoordinateGrid": true,
                "frameRateLimit": 60,
                "nativeBenchmarkIterations": 200
              }
            }
        """.trimIndent()
        val decoded = ConfigJson.decode(legacy)
        assertEquals(true, decoded.developer.enabled)
        assertEquals(AppLogLevel.INFO, decoded.developer.logLevel)
        assertEquals(true, decoded.developer.showCoordinateGrid)
    }
}
