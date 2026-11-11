package id.phonelink.android.core

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** One source in a scene, which a button can show or hide. */
data class ObsSceneItem(val id: Int, val source: String, val enabled: Boolean)

data class ObsState(
    val status: Status = Status.Off,
    val error: String? = null,
    val version: String? = null,
    val scenes: List<String> = emptyList(),
    val currentScene: String? = null,
    val streaming: Boolean = false,
    val recording: Boolean = false,
    val recordPaused: Boolean = false,
    val virtualCam: Boolean = false,
    val replayBuffer: Boolean = false,
    /** Inputs that have audio, with whether each is muted. */
    val audio: Map<String, Boolean> = emptyMap(),
    val items: Map<String, List<ObsSceneItem>> = emptyMap(),
) {
    enum class Status { Off, Connecting, Connected, Failed }
}

/**
 * What an OBS button does, stored in a Deck button's value: "scene:Name", "stream", "record",
 * "record_pause", "replay", "virtualcam", "transition", "mute:Input", or "source:Scene\tSource".
 */
sealed interface ObsAction {
    data class Scene(val name: String) : ObsAction
    data object Stream : ObsAction
    data object Record : ObsAction
    data object RecordPause : ObsAction
    data object Replay : ObsAction
    data object VirtualCam : ObsAction
    data object Transition : ObsAction
    data class Mute(val input: String) : ObsAction
    data class Source(val scene: String, val source: String) : ObsAction

    fun encode(): String = when (this) {
        is Scene -> "scene:$name"
        Stream -> "stream"
        Record -> "record"
        RecordPause -> "record_pause"
        Replay -> "replay"
        VirtualCam -> "virtualcam"
        Transition -> "transition"
        is Mute -> "mute:$input"
        is Source -> "source:$scene\t$source"
    }

    companion object {
        fun decode(value: String): ObsAction? = when {
            value.startsWith("scene:") -> value.removePrefix("scene:").takeIf { it.isNotEmpty() }?.let(::Scene)
            value.startsWith("mute:") -> value.removePrefix("mute:").takeIf { it.isNotEmpty() }?.let(::Mute)
            value.startsWith("source:") -> value.removePrefix("source:").split('\t', limit = 2)
                .takeIf { it.size == 2 && it.all(String::isNotEmpty) }?.let { Source(it[0], it[1]) }
            value == "stream" -> Stream
            value == "record" -> Record
            value == "record_pause" -> RecordPause
            value == "replay" -> Replay
            value == "virtualcam" -> VirtualCam
            value == "transition" -> Transition
            else -> null
        }
    }
}

object ObsProtocol {
    const val DEFAULT_PORT = 4455
    const val RPC_VERSION = 1

    /** Scenes, inputs, outputs, scene items, and UI events. High-volume events like volume meters are left out. */
    const val EVENTS = 4 or 8 or 64 or 128 or 1024

    private fun sha256Base64(text: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    /** obs-websocket 5: base64(sha256(base64(sha256(password + salt)) + challenge)). */
    fun authentication(password: String, salt: String, challenge: String): String =
        sha256Base64(sha256Base64(password + salt) + challenge)

    fun label(action: ObsAction): String = when (action) {
        is ObsAction.Scene -> "Scene: ${action.name}"
        ObsAction.Stream -> "Start / stop stream"
        ObsAction.Record -> "Start / stop recording"
        ObsAction.RecordPause -> "Pause recording"
        ObsAction.Replay -> "Save replay"
        ObsAction.VirtualCam -> "Virtual camera"
        ObsAction.Transition -> "Studio mode transition"
        is ObsAction.Mute -> "Mute ${action.input}"
        is ObsAction.Source -> "Show / hide ${action.source}"
    }

    /** Whether a button should look "on": the live scene, a running output, a muted input, a visible source. */
    fun isActive(action: ObsAction, state: ObsState): Boolean = when (action) {
        is ObsAction.Scene -> state.currentScene == action.name
        ObsAction.Stream -> state.streaming
        ObsAction.Record -> state.recording
        ObsAction.RecordPause -> state.recordPaused
        ObsAction.VirtualCam -> state.virtualCam
        is ObsAction.Mute -> state.audio[action.input] == true
        is ObsAction.Source -> state.items[action.scene]?.firstOrNull { it.source == action.source }?.enabled == true
        ObsAction.Replay, ObsAction.Transition -> false
    }

    /** Whether the button can work with what OBS has now, such as a scene that still exists. */
    fun isAvailable(action: ObsAction, state: ObsState): Boolean = when (action) {
        is ObsAction.Scene -> action.name in state.scenes
        is ObsAction.Mute -> action.input in state.audio
        is ObsAction.Source -> state.items[action.scene]?.any { it.source == action.source } == true
        else -> true
    }

    /** Applies one OBS event to the state. Returns null when the event needs a full refresh instead. */
    fun apply(state: ObsState, type: String, data: JsonObject?): ObsState? {
        fun bool(key: String) = data?.get(key)?.jsonPrimitive?.booleanOrNull
        fun text(key: String) = data?.get(key)?.jsonPrimitive?.contentOrNull
        return when (type) {
            "CurrentProgramSceneChanged" -> state.copy(currentScene = text("sceneName") ?: state.currentScene)
            "StreamStateChanged" -> state.copy(streaming = bool("outputActive") ?: state.streaming)
            "RecordStateChanged" -> {
                val output = text("outputState")
                state.copy(
                    recording = bool("outputActive") ?: state.recording,
                    recordPaused = when (output) {
                        "OBS_WEBSOCKET_OUTPUT_PAUSED" -> true
                        "OBS_WEBSOCKET_OUTPUT_RESUMED", "OBS_WEBSOCKET_OUTPUT_STOPPED", "OBS_WEBSOCKET_OUTPUT_STARTED" -> false
                        else -> state.recordPaused
                    },
                )
            }
            "VirtualcamStateChanged" -> state.copy(virtualCam = bool("outputActive") ?: state.virtualCam)
            "ReplayBufferStateChanged" -> state.copy(replayBuffer = bool("outputActive") ?: state.replayBuffer)
            "InputMuteStateChanged" -> {
                val input = text("inputName") ?: return state
                val muted = bool("inputMuted") ?: return state
                state.copy(audio = state.audio + (input to muted))
            }
            "SceneItemEnableStateChanged" -> {
                val scene = text("sceneName") ?: return state
                val id = data?.get("sceneItemId")?.jsonPrimitive?.intOrNull ?: return state
                val enabled = bool("sceneItemEnabled") ?: return state
                state.copy(items = state.items + (scene to state.items[scene].orEmpty().map { if (it.id == id) it.copy(enabled = enabled) else it }))
            }
            "SceneListChanged", "SceneCreated", "SceneRemoved", "SceneNameChanged",
            "InputCreated", "InputRemoved", "InputNameChanged",
            "SceneItemCreated", "SceneItemRemoved", "SceneItemListReindexed",
            -> null
            else -> state
        }
    }
}

/** A connection to OBS's built-in WebSocket server. Talks to OBS directly, so it works without the Mac. */
class ObsClient(private val http: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(ObsState())
    val state: StateFlow<ObsState> = _state.asStateFlow()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, JsonObject?>>>()
    private var socket: WebSocket? = null
    private var password = ""
    private var generation = 0

    fun connect(host: String, port: Int, password: String) {
        disconnect()
        this.password = password
        val current = ++generation
        _state.value = ObsState(status = ObsState.Status.Connecting)
        val target = host.trim().removePrefix("ws://")
        val request = runCatching { Request.Builder().url("ws://$target:$port").build() }.getOrElse {
            _state.value = ObsState(status = ObsState.Status.Failed, error = "That address doesn't look right.")
            return
        }
        socket = http.newWebSocket(request, Listener(current))
    }

    fun disconnect() {
        generation++
        socket?.close(1000, null)
        socket = null
        failPending()
        _state.value = ObsState()
    }

    private fun failPending() {
        pending.values.forEach { it.complete(false to null) }
        pending.clear()
    }

    /** Sends one request and waits for OBS's answer. */
    suspend fun request(type: String, data: JsonObject? = null): Pair<Boolean, JsonObject?> {
        val socket = socket ?: return false to null
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<Pair<Boolean, JsonObject?>>()
        pending[id] = reply
        val message = buildJsonObject {
            put("op", 6)
            put("d", buildJsonObject {
                put("requestType", type)
                put("requestId", id)
                data?.let { put("requestData", it) }
            })
        }
        if (!socket.send(message.toString())) {
            pending.remove(id)
            return false to null
        }
        return withTimeoutOrNull(5_000) { reply.await() } ?: run {
            pending.remove(id)
            false to null
        }
    }

    fun run(action: ObsAction) {
        scope.launch {
            when (action) {
                is ObsAction.Scene -> request("SetCurrentProgramScene", obj("sceneName" to action.name))
                ObsAction.Stream -> request("ToggleStream")
                ObsAction.Record -> request("ToggleRecord")
                ObsAction.RecordPause -> request("ToggleRecordPause")
                ObsAction.Replay -> request("SaveReplayBuffer")
                ObsAction.VirtualCam -> request("ToggleVirtualCam")
                ObsAction.Transition -> request("TriggerStudioModeTransition")
                is ObsAction.Mute -> request("ToggleInputMute", obj("inputName" to action.input))
                is ObsAction.Source -> {
                    val item = _state.value.items[action.scene]?.firstOrNull { it.source == action.source } ?: return@launch
                    request(
                        "SetSceneItemEnabled",
                        buildJsonObject {
                            put("sceneName", action.scene)
                            put("sceneItemId", item.id)
                            put("sceneItemEnabled", !item.enabled)
                        },
                    )
                }
            }
        }
    }

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (key, value) -> key to JsonPrimitive(value) })

    /** Reads everything the buttons need: scenes, outputs, audio inputs, and each scene's sources. */
    private suspend fun refresh() {
        val (scenesOk, scenes) = request("GetSceneList")
        if (!scenesOk || scenes == null) return
        // OBS lists scenes bottom to top, the reverse of its Scenes panel.
        val names = scenes["scenes"]?.jsonArray?.mapNotNull { it.jsonObject["sceneName"]?.jsonPrimitive?.contentOrNull }?.reversed().orEmpty()
        val current = scenes["currentProgramSceneName"]?.jsonPrimitive?.contentOrNull
        fun active(reply: Pair<Boolean, JsonObject?>, key: String = "outputActive") = reply.second?.get(key)?.jsonPrimitive?.booleanOrNull == true
        val stream = request("GetStreamStatus")
        val record = request("GetRecordStatus")
        val virtualCam = request("GetVirtualCamStatus")
        val replay = request("GetReplayBufferStatus")
        val inputs = request("GetInputList").second?.get("inputs")?.jsonArray
            ?.mapNotNull { it.jsonObject["inputName"]?.jsonPrimitive?.contentOrNull }.orEmpty()
        // Only inputs with audio answer GetInputMute, which is how audio inputs are told apart.
        val audio = inputs.mapNotNull { input ->
            val (ok, reply) = request("GetInputMute", obj("inputName" to input))
            if (ok) input to (reply?.get("inputMuted")?.jsonPrimitive?.booleanOrNull == true) else null
        }.toMap()
        val items = names.associateWith { scene ->
            request("GetSceneItemList", obj("sceneName" to scene)).second?.get("sceneItems")?.jsonArray?.mapNotNull {
                val item = it.jsonObject
                ObsSceneItem(
                    item["sceneItemId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    item["sourceName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    item["sceneItemEnabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }?.reversed().orEmpty()
        }
        _state.update {
            it.copy(
                scenes = names,
                currentScene = current,
                streaming = active(stream),
                recording = active(record),
                recordPaused = active(record, "outputPaused"),
                virtualCam = active(virtualCam),
                replayBuffer = active(replay),
                audio = audio,
                items = items,
            )
        }
    }

    private inner class Listener(private val id: Int) : WebSocketListener() {
        private fun current() = id == generation

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!current()) return
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val op = root["op"]?.jsonPrimitive?.intOrNull ?: return
            val d = root["d"] as? JsonObject ?: return
            when (op) {
                0 -> hello(webSocket, d)
                2 -> {
                    _state.update { it.copy(status = ObsState.Status.Connected, error = null) }
                    scope.launch { refresh() }
                }
                5 -> {
                    val type = d["eventType"]?.jsonPrimitive?.contentOrNull ?: return
                    val data = d["eventData"] as? JsonObject
                    val next = ObsProtocol.apply(_state.value, type, data)
                    if (next == null) scope.launch { refresh() } else _state.value = next
                }
                7 -> {
                    val requestId = d["requestId"]?.jsonPrimitive?.contentOrNull ?: return
                    val ok = (d["requestStatus"] as? JsonObject)?.get("result")?.jsonPrimitive?.booleanOrNull == true
                    pending.remove(requestId)?.complete(ok to (d["responseData"] as? JsonObject))
                }
            }
        }

        private fun hello(webSocket: WebSocket, d: JsonObject) {
            val auth = d["authentication"] as? JsonObject
            val version = d["obsWebSocketVersion"]?.jsonPrimitive?.contentOrNull
            _state.update { it.copy(version = version) }
            val identify = buildJsonObject {
                put("op", 1)
                put("d", buildJsonObject {
                    put("rpcVersion", ObsProtocol.RPC_VERSION)
                    put("eventSubscriptions", ObsProtocol.EVENTS)
                    if (auth != null) {
                        val salt = auth["salt"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val challenge = auth["challenge"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        put("authentication", ObsProtocol.authentication(password, salt, challenge))
                    }
                })
            }
            webSocket.send(identify.toString())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            closed(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(code, reason)

        private fun closed(code: Int, reason: String) {
            if (!current()) return
            failPending()
            val message = when (code) {
                4009 -> "Wrong password. Check it in OBS under Tools > WebSocket Server Settings."
                4010 -> "This OBS is too old. obs-websocket 5 comes with OBS 28 and newer."
                1000, 1001 -> "OBS closed the connection."
                else -> reason.ifBlank { "OBS closed the connection ($code)." }
            }
            _state.value = ObsState(status = ObsState.Status.Failed, error = message)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!current()) return
            failPending()
            _state.value = ObsState(
                status = ObsState.Status.Failed,
                error = "Couldn't reach OBS. Check that OBS is open, the WebSocket server is on, and the address and port are right.",
            )
        }
    }

    companion object {
        /** One connection for the whole app, so it stays up while moving between screens. */
        val shared: ObsClient by lazy { ObsClient() }
    }
}
