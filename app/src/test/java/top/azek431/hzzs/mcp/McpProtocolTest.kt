package top.azek431.hzzs.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 协议 / 会话 / HTTP 辅助契约测试（纯 JVM）。
 * 不启动真实 ServerSocket，覆盖握手、通知、会话与鉴权辅助。
 */
class McpProtocolTest {
    @Test
    fun loopbackOriginAllowedAndForeignRejected() {
        assertTrue(isAllowedLoopbackOrigin(null))
        assertTrue(isAllowedLoopbackOrigin(""))
        assertTrue(isAllowedLoopbackOrigin("http://127.0.0.1:1234"))
        assertTrue(isAllowedLoopbackOrigin("http://localhost"))
        assertTrue(isAllowedLoopbackOrigin("https://[::1]"))
        assertFalse(isAllowedLoopbackOrigin("http://evil.example"))
        assertFalse(isAllowedLoopbackOrigin("http://192.168.1.1"))
        assertFalse(isAllowedLoopbackOrigin("file://localhost"))
    }

    @Test
    fun bearerConstantTimeMatch() {
        val token = "aabbccddeeff00112233445566778899"
        assertTrue(constantTimeBearerMatches("Bearer $token", token))
        assertFalse(constantTimeBearerMatches("Bearer wrong", token))
        assertFalse(constantTimeBearerMatches(token, token)) // 缺少 Bearer 前缀
        assertFalse(constantTimeBearerMatches(null, token))
        assertFalse(constantTimeBearerMatches("Bearer $token", token + "x"))
    }

    @Test
    fun sessionCreateInitializeAndExpire() {
        val manager = McpSessionManager()
        val session = manager.createSession(McpProtocolVersions.LATEST, "test-client")
        assertNotNull(manager.get(session.id))
        assertFalse(session.initialized)
        assertTrue(manager.markInitialized(session.id))
        assertTrue(manager.get(session.id)!!.initialized)
        manager.remove(session.id)
        assertEquals(null, manager.get(session.id))
    }

    @Test
    fun connectionBackpressure() {
        val manager = McpSessionManager()
        repeat(McpLimits.MAX_CONCURRENT_CONNECTIONS) {
            assertTrue(manager.tryAcquireConnection())
        }
        assertFalse(manager.tryAcquireConnection())
        manager.releaseConnection()
        assertTrue(manager.tryAcquireConnection())
    }

    @Test
    fun generationClearsSessions() {
        val manager = McpSessionManager()
        val session = manager.createSession(McpProtocolVersions.LATEST, "c")
        manager.bumpGeneration()
        assertEquals(null, manager.get(session.id))
    }

    @Test
    fun toolCatalogHasStrictSchemas() {
        McpToolCatalog.tools.forEach { tool ->
            assertEquals("object", tool.inputSchema.getString("type"))
            assertFalse(
                "tool ${tool.name} must not open additionalProperties",
                tool.inputSchema.optBoolean("additionalProperties", true),
            )
        }
        assertTrue(McpToolCatalog.tools.any { it.name == "navigate" })
        assertTrue(McpToolCatalog.tools.any { it.name == "list_debug_frames" })
        assertNotNull(McpToolCatalog.tool("get_status"))
        assertEquals(null, McpToolCatalog.tool("arm_automation"))
    }

    @Test
    fun initializeCreatesSessionAndInitializedAccepts() = runBlocking {
        val sessions = McpSessionManager()
        val protocol = McpProtocol(
            sessions = sessions,
            actions = FakeActions(),
            serverVersion = "0.1.0-test",
        )
        val init = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "initialize")
                .put(
                    "params",
                    JSONObject()
                        .put("protocolVersion", McpProtocolVersions.LATEST)
                        .put("capabilities", JSONObject())
                        .put(
                            "clientInfo",
                            JSONObject().put("name", "RikkaHub").put("version", "1.0"),
                        ),
                ),
            existingSessionId = null,
            protocolVersionHeader = null,
        )
        val initResp = init as McpProtocol.DispatchResult.JsonResponse
        assertEquals(200, initResp.status)
        assertNotNull(initResp.sessionId)
        val negotiated = initResp.body.getJSONObject("result").getString("protocolVersion")
        assertEquals(McpProtocolVersions.LATEST, negotiated)

        val note = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized"),
            existingSessionId = initResp.sessionId,
            protocolVersionHeader = negotiated,
        )
        assertTrue(note is McpProtocol.DispatchResult.Accepted)
        assertTrue(sessions.get(initResp.sessionId)!!.initialized)
    }

    @Test
    fun notificationWithoutIdReturnsAccepted() = runBlocking {
        val sessions = McpSessionManager()
        val session = sessions.createSession(McpProtocolVersions.LATEST, "c")
        sessions.markInitialized(session.id)
        val protocol = McpProtocol(sessions, FakeActions(), serverVersion = "t")
        val result = protocol.dispatch(
            JSONObject().put("jsonrpc", "2.0").put("method", "notifications/cancelled"),
            existingSessionId = session.id,
            protocolVersionHeader = McpProtocolVersions.LATEST,
        )
        assertTrue(result is McpProtocol.DispatchResult.Accepted)
    }

    @Test
    fun toolsCallRequiresInitializedSession() = runBlocking {
        val sessions = McpSessionManager()
        val session = sessions.createSession(McpProtocolVersions.LATEST, "c")
        val protocol = McpProtocol(sessions, FakeActions(), serverVersion = "t")
        val result = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject()
                        .put("name", "get_status")
                        .put("arguments", JSONObject()),
                ),
            existingSessionId = session.id,
            protocolVersionHeader = McpProtocolVersions.LATEST,
        )
        val body = (result as McpProtocol.DispatchResult.JsonResponse).body
        assertTrue(body.has("error"))
        assertEquals(McpErrorCodes.NOT_INITIALIZED, body.getJSONObject("error").getInt("code"))
    }

    @Test
    fun toolsCallAfterHandshakeReturnsContent() = runBlocking {
        val sessions = McpSessionManager()
        val session = sessions.createSession(McpProtocolVersions.LATEST, "OperitAI")
        sessions.markInitialized(session.id)
        val protocol = McpProtocol(sessions, FakeActions(), serverVersion = "t")
        val result = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 9)
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject()
                        .put("name", "get_status")
                        .put("arguments", JSONObject()),
                ),
            existingSessionId = session.id,
            protocolVersionHeader = McpProtocolVersions.LATEST,
        )
        val body = (result as McpProtocol.DispatchResult.JsonResponse).body
        assertTrue(body.has("result"))
        assertTrue(body.getJSONObject("result").has("content"))
    }

    @Test
    fun unknownMethodReturnsMethodNotFound() = runBlocking {
        val sessions = McpSessionManager()
        val session = sessions.createSession(McpProtocolVersions.LATEST, "c")
        sessions.markInitialized(session.id)
        val protocol = McpProtocol(sessions, FakeActions(), serverVersion = "t")
        val result = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 3)
                .put("method", "prompts/list"),
            existingSessionId = session.id,
            protocolVersionHeader = null,
        )
        val err = (result as McpProtocol.DispatchResult.JsonResponse).body.getJSONObject("error")
        assertEquals(McpErrorCodes.METHOD_NOT_FOUND, err.getInt("code"))
    }

    @Test
    fun unsupportedProtocolHeaderRejected() = runBlocking {
        val sessions = McpSessionManager()
        val session = sessions.createSession(McpProtocolVersions.LATEST, "c")
        sessions.markInitialized(session.id)
        val protocol = McpProtocol(sessions, FakeActions(), serverVersion = "t")
        val result = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 4)
                .put("method", "tools/list"),
            existingSessionId = session.id,
            protocolVersionHeader = "1.0.0",
        )
        assertTrue(result is McpProtocol.DispatchResult.HttpError)
        assertEquals(400, (result as McpProtocol.DispatchResult.HttpError).status)
    }

    @Test
    fun missingSessionRejectedForToolCallButListAllowed() = runBlocking {
        val protocol = McpProtocol(McpSessionManager(), FakeActions(), serverVersion = "t")
        val call = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 5)
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject().put("name", "get_status").put("arguments", JSONObject()),
                ),
            existingSessionId = null,
            protocolVersionHeader = null,
        )
        assertTrue(call is McpProtocol.DispatchResult.HttpError)
        assertEquals(400, (call as McpProtocol.DispatchResult.HttpError).status)

        val list = protocol.dispatch(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 6)
                .put("method", "tools/list"),
            existingSessionId = null,
            protocolVersionHeader = null,
        )
        assertTrue(list is McpProtocol.DispatchResult.JsonResponse)
        assertTrue((list as McpProtocol.DispatchResult.JsonResponse).body.has("result"))
    }

    private class FakeActions : McpActionSurface {
        override suspend fun readResource(uri: String): JSONObject =
            JSONObject().put("uri", uri)

        override suspend fun call(
            tool: String,
            arguments: JSONObject,
            session: McpSessionManager.Session?,
        ): JSONObject = JSONObject().put("ok", true).put("tool", tool)
    }
}
