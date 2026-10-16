package one.meshcore.shared.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import one.meshcore.shared.model.Uuid

/**
 * A way to reach a MeshCore radio. Mirrors the Swift `ConnectionMethod` enum with associated values,
 * whose synthesized `Codable` shape is a single-key wrapper object: `{"wifi": {...}}` /
 * `{"bluetooth": {...}}`. kotlinx's default sealed-class encoding does not match that shape, so
 * [ConnectionMethodSerializer] handles it. Backups strip Bluetooth methods (`redactedForBackup`), so
 * the fixtures carry only WiFi, but both cases decode to support live (non-backup) payloads.
 */
@Serializable(with = ConnectionMethodSerializer::class)
sealed interface ConnectionMethod {
    data class WiFi(val host: String, val port: Int, val displayName: String?) : ConnectionMethod

    data class Bluetooth(val peripheralUuid: Uuid, val displayName: String?) : ConnectionMethod
}

/** Inner object of the `{"wifi": {...}}` wrapper. Keys match the Swift associated-value labels. */
@Serializable
private data class WiFiPayload(val host: String, val port: Int, val displayName: String? = null)

/** Inner object of the `{"bluetooth": {...}}` wrapper. Keys match the Swift associated-value labels. */
@Serializable
private data class BluetoothPayload(val peripheralUUID: Uuid, val displayName: String? = null)

object ConnectionMethodSerializer : KSerializer<ConnectionMethod> {
    private const val WIFI_KEY = "wifi"
    private const val BLUETOOTH_KEY = "bluetooth"

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ConnectionMethod")

    override fun deserialize(decoder: Decoder): ConnectionMethod {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ConnectionMethod is only decodable from JSON")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        return when {
            WIFI_KEY in obj -> {
                val payload = jsonDecoder.json.decodeFromJsonElement(WiFiPayload.serializer(), obj.getValue(WIFI_KEY))
                ConnectionMethod.WiFi(payload.host, payload.port, payload.displayName)
            }
            BLUETOOTH_KEY in obj -> {
                val payload =
                    jsonDecoder.json.decodeFromJsonElement(BluetoothPayload.serializer(), obj.getValue(BLUETOOTH_KEY))
                ConnectionMethod.Bluetooth(payload.peripheralUUID, payload.displayName)
            }
            else -> throw SerializationException("Unknown ConnectionMethod wrapper key: ${obj.keys}")
        }
    }

    override fun serialize(encoder: Encoder, value: ConnectionMethod) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ConnectionMethod is only encodable to JSON")
        val element =
            when (value) {
                is ConnectionMethod.WiFi ->
                    buildJsonObject {
                        put(
                            WIFI_KEY,
                            jsonEncoder.json.encodeToJsonElement(
                                WiFiPayload.serializer(),
                                WiFiPayload(value.host, value.port, value.displayName),
                            ),
                        )
                    }
                is ConnectionMethod.Bluetooth ->
                    buildJsonObject {
                        put(
                            BLUETOOTH_KEY,
                            jsonEncoder.json.encodeToJsonElement(
                                BluetoothPayload.serializer(),
                                BluetoothPayload(value.peripheralUuid, value.displayName),
                            ),
                        )
                    }
            }
        jsonEncoder.encodeJsonElement(element)
    }
}
