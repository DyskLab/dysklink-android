package id.phonelink.android.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fake OBS that speaks obs-websocket 5, enough to check the client end to end. */
private class FakeObs(private val password: String?) : WebSocketListener() {
    val requests = CopyOnWriteArrayList<Pair<String, JsonObject?>>()
    var socket: WebSocket? = null
    private val salt = "c2FsdA=="
    private val challenge = "Y2hhbGxlbmdl"

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
        val auth = if (password != null) ""","authentication":{"challenge":"$challenge","salt":"$salt"}""" else ""
        webSocket.send("""{"op":0,"d":{"obsWebSocketVersion":"5.5.0","rpcVersion":1$auth}}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val root = Json.parseToJsonElement(text).jsonObject
        val d = root["d"]!!.jsonObject
        when (root["op"]!!.jsonPrimitive.int) {
            1 -> {
                val sent = d["authentication"]?.jsonPrimitive?.contentOrNull
                if (password != null && sent != ObsProtocol.authentication(password, salt, challenge)) {
                    webSocket.close(4009, "Authentication failed.")
                } else {
                    webSocket.send("""{"op":2,"d":{"negotiatedRpcVersion":1}}""")
                }
            }
            6 -> {
                val type = d["requestType"]!!.jsonPrimitive.content
                val id = d["requestId"]!!.jsonPrimitive.content
                requests += type to (d["requestData"] as? JsonObject)
                val input = (d["requestData"] as? JsonObject)?.get("inputName")?.jsonPrimitive?.contentOrNull
                val (ok, data) = when (type) {
                    "GetSceneList" -> true to """{"currentProgramSceneName":"Main","scenes":[{"sceneName":"BRB","sceneIndex":0},{"sceneName":"Main","sceneIndex":1}]}"""
                    "GetStreamStatus" -> true to """{"outputActive":false}"""
                    "GetRecordStatus" -> true to """{"outputActive":true,"outputPaused":false}"""
                    "GetVirtualCamStatus" -> true to """{"outputActive":false}"""
                    "GetReplayBufferStatus" -> false to "null"
                    "GetInputList" -> true to """{"inputs":[{"inputName":"Mic"},{"inputName":"Camera"}]}"""
                    "GetInputMute" -> if (input == "Mic") true to """{"inputMuted":false}""" else false to "null"
                    "GetSceneItemList" -> true to """{"sceneItems":[{"sceneItemId":7,"sourceName":"Camera","sceneItemEnabled":true}]}"""
                    else -> true to "null"
                }
                webSocket.send("""{"op":7,"d":{"requestType":"$type","requestId":"$id","requestStatus":{"result":$ok,"code":${if (ok) 100 else 600}},"responseData":$data}}""")
            }
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
    }

    fun event(type: String, data: String) {
        socket!!.send("""{"op":5,"d":{"eventType":"$type","eventIntent":4,"eventData":$data}}""")
    }
}

class ObsTest {
    private val server = MockWebServer()

    @AfterTest
    fun stop() = server.shutdown()

    private fun start(obs: FakeObs): ObsClient {
        server.enqueue(MockResponse().withWebSocketUpgrade(obs))
        server.start()
        return ObsClient()
    }

    private fun waitFor(client: ObsClient, check: (ObsState) -> Boolean): ObsState = runBlocking {
        withTimeout(5_000) { client.state.first(check) }
    }

    @Test
    fun computesTheDocumentedAuthentication() {
        // From the obs-websocket 5 protocol: base64(sha256(base64(sha256(password + salt)) + challenge)).
        val secret = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest("supersecretpasswordlUs9wOnEfo5sFx3y2fw3Ss2Ng9sClgUOw".toByteArray()),
        )
        val expected = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest((secret + "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY=").toByteArray()),
        )
        assertEquals(expected, ObsProtocol.authentication("supersecretpassword", "lUs9wOnEfo5sFx3y2fw3Ss2Ng9sClgUOw", "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="))
    }

    @Test
    fun connectsReadsStateAndRunsActions() {
        val obs = FakeObs("rahasia")
        val client = start(obs)
        client.connect(server.hostName, server.port, "rahasia")
        val ready = waitFor(client) { it.status == ObsState.Status.Connected && it.scenes.isNotEmpty() }
        // The Scenes panel order: top first.
        assertEquals(listOf("Main", "BRB"), ready.scenes)
        assertEquals("Main", ready.currentScene)
        assertTrue(ready.recording)
        assertFalse(ready.streaming)
        assertEquals(mapOf("Mic" to false), ready.audio)
        assertEquals(7, ready.items["Main"]?.single()?.id)

        obs.event("CurrentProgramSceneChanged", """{"sceneName":"BRB"}""")
        obs.event("StreamStateChanged", """{"outputActive":true,"outputState":"OBS_WEBSOCKET_OUTPUT_STARTED"}""")
        obs.event("InputMuteStateChanged", """{"inputName":"Mic","inputMuted":true}""")
        val live = waitFor(client) { it.currentScene == "BRB" && it.streaming && it.audio["Mic"] == true }
        assertTrue(ObsProtocol.isActive(ObsAction.Scene("BRB"), live))
        assertTrue(ObsProtocol.isActive(ObsAction.Mute("Mic"), live))

        client.run(ObsAction.Scene("Main"))
        client.run(ObsAction.Source("Main", "Camera"))
        client.run(ObsAction.Stream)
        runBlocking {
            withTimeout(5_000) { while (!listOf("ToggleStream", "SetCurrentProgramScene", "SetSceneItemEnabled").all { type -> obs.requests.any { it.first == type } }) kotlinx.coroutines.delay(20) }
        }
        assertEquals("Main", obs.requests.first { it.first == "SetCurrentProgramScene" }.second?.get("sceneName")?.jsonPrimitive?.content)
        val hide = obs.requests.first { it.first == "SetSceneItemEnabled" }.second!!
        assertEquals("7", hide["sceneItemId"]?.jsonPrimitive?.content)
        assertEquals("false", hide["sceneItemEnabled"]?.jsonPrimitive?.content)
        client.disconnect()
        assertEquals(ObsState.Status.Off, client.state.value.status)
    }

    @Test
    fun saysWhenThePasswordIsWrong() {
        val client = start(FakeObs("benar"))
        client.connect(server.hostName, server.port, "salah")
        val failed = waitFor(client) { it.status == ObsState.Status.Failed }
        assertTrue(failed.error!!.startsWith("Wrong password"))
    }

    @Test
    fun encodesActions() {
        val actions = listOf(
            ObsAction.Scene("Main Cam"), ObsAction.Stream, ObsAction.Record, ObsAction.RecordPause, ObsAction.Replay,
            ObsAction.VirtualCam, ObsAction.Transition, ObsAction.Mute("Mic/Aux"), ObsAction.Source("Scene 1", "Logo: top"),
        )
        actions.forEach { assertEquals(it, ObsAction.decode(it.encode())) }
        assertNull(ObsAction.decode("scene:"))
        assertNull(ObsAction.decode("source:onlyscene"))
        assertNull(ObsAction.decode("explode"))
        assertTrue(Deck.isValid(DeckButton("o", "Live", "📡", 4, DeckKind.Obs, "stream")))
        assertFalse(Deck.needsMac(DeckButton("o", "Live", "📡", 4, DeckKind.Obs, "stream")))
        val state = ObsState(scenes = listOf("Main"))
        assertFalse(ObsProtocol.isAvailable(ObsAction.Scene("Gone"), state))
        assertNull(ObsProtocol.apply(state, "SceneListChanged", null))
    }
}
