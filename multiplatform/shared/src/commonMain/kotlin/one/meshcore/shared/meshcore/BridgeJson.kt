package one.meshcore.shared.meshcore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Kotlin half of the frozen JNI boundary encoding (v1). The Swift `MeshCoreBridge` is the
 * producer of record; this reader mirrors `MeshCoreBridge/ENCODING.md` field-for-field and is
 * pinned to the golden vectors under `fixtures/bridge/`.
 *
 * The Swift producer emits a fixed key set (a nil optional is an explicit JSON `null`, never a
 * missing key), so decode never depends on key omission. `ignoreUnknownKeys` still lets a future
 * producer append a field without breaking this reader.
 */
internal val bridgeJson: Json =
    Json {
        ignoreUnknownKeys = true
    }

/** The only envelope version this reader understands. */
internal const val BRIDGE_ENVELOPE_VERSION: Int = 1

/** Thrown when a boundary envelope is malformed or carries an unknown version or kind. */
class BridgeDecodingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Splits a versioned envelope `{"v":1,"kind":…,"payload":{…}}` into its kind and payload object,
 * rejecting any other version. Shared by the event and result decoders.
 */
internal fun splitEnvelope(jsonText: String): Pair<String, JsonObject> {
    fun malformed(detail: String, cause: Throwable? = null): Nothing = throw BridgeDecodingException(detail, cause)
    val root =
        try {
            bridgeJson.parseToJsonElement(jsonText).jsonObject
        } catch (e: IllegalArgumentException) {
            malformed("bridge envelope is not a JSON object", e)
        }
    val version = root["v"]?.jsonPrimitive?.content?.toIntOrNull()
    if (version != BRIDGE_ENVELOPE_VERSION) {
        malformed("unsupported bridge envelope version: $version")
    }
    val kind =
        root["kind"]?.jsonPrimitive?.content
            ?: malformed("bridge envelope is missing 'kind'")
    val payload =
        root["payload"] as? JsonObject
            ?: malformed("bridge envelope '$kind' is missing an object 'payload'")
    return kind to payload
}

/** Payload decode used by the generated event/result readers. */
internal inline fun <reified T> JsonObject.decodeBridge(): T = bridgeJson.decodeFromJsonElement(this)
