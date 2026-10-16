package one.meshcore.shared.settings

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The radio's JSON configuration, companion-app-compatible, mirroring the Swift `MeshCoreNodeConfig`. This
 * is the node-config wire format (§9) — SEPARATE from the app's backup envelope. All sections are optional;
 * an absent section is omitted (the [NodeConfigJson] encoder uses `explicitNulls = false`), except
 * [ContactConfig]'s `custom_name`/`out_path`, which are always emitted as explicit `null` per the companion
 * app's expectation. Snake_case keys are pinned via [SerialName].
 */
@Serializable
data class MeshCoreNodeConfig(
    val name: String? = null,
    @SerialName("public_key") val publicKey: String? = null,
    @SerialName("private_key") val privateKey: String? = null,
    @SerialName("radio_settings") val radioSettings: RadioSettings? = null,
    @SerialName("position_settings") val positionSettings: PositionSettings? = null,
    @SerialName("other_settings") val otherSettings: OtherSettings? = null,
    val channels: List<ChannelConfig>? = null,
    val contacts: List<ContactConfig>? = null,
) {
    /** Radio parameters; `frequency` is kHz and `bandwidth` is Hz (both UInt32), `txPower` is dBm (Int8). */
    @Serializable
    data class RadioSettings(
        val frequency: UInt,
        val bandwidth: UInt,
        @SerialName("spreading_factor") val spreadingFactor: UByte,
        @SerialName("coding_rate") val codingRate: UByte,
        @SerialName("tx_power") val txPower: Byte,
    )

    /** Latitude/longitude as strings, matching the companion format. */
    @Serializable
    data class PositionSettings(val latitude: String, val longitude: String) {
        /** Both coordinates parse to zero (likely unset). */
        val isZero: Boolean
            get() = (latitude.toDoubleOrNull() ?: 0.0) == 0.0 && (longitude.toDoubleOrNull() ?: 0.0) == 0.0
    }

    /**
     * Other device parameters. Only `manual_add_contacts` and `advert_location_policy` are exported
     * (companion parity); all seven are decoded on import for forward compatibility.
     */
    @Serializable
    data class OtherSettings(
        @SerialName("manual_add_contacts") val manualAddContacts: UByte? = null,
        @SerialName("advert_location_policy") val advertLocationPolicy: UByte? = null,
        @SerialName("telemetry_mode_base") val telemetryModeBase: UByte? = null,
        @SerialName("telemetry_mode_location") val telemetryModeLocation: UByte? = null,
        @SerialName("telemetry_mode_environment") val telemetryModeEnvironment: UByte? = null,
        @SerialName("multi_acks") val multiAcks: UByte? = null,
        @SerialName("advertisement_type") val advertisementType: UByte? = null,
    )

    /** A channel: name plus a hex-encoded 16-byte secret (32 hex chars). */
    @Serializable
    data class ChannelConfig(val name: String, val secret: String)

    /**
     * A contact. `custom_name` (app-local nickname, not imported to firmware) and `out_path` are always
     * emitted as explicit `null` when absent; `path_hash_mode` is omitted when null. This exact asymmetry is
     * the companion-app freeze, so [ContactConfig] uses [ContactConfigSerializer] rather than generated code.
     */
    @Serializable(with = ContactConfigSerializer::class)
    data class ContactConfig(
        val type: UByte,
        val name: String,
        val customName: String? = null,
        val publicKey: String,
        val flags: UByte,
        val latitude: String,
        val longitude: String,
        val lastAdvert: UInt,
        val lastModified: UInt,
        val outPath: String? = null,
        val pathHashMode: UByte? = null,
    )
}

/**
 * Freezes [MeshCoreNodeConfig.ContactConfig]'s JSON shape: `custom_name`/`out_path` are always present (their
 * value or explicit `null`), while `path_hash_mode` is omitted when null. Mirrors the Swift custom
 * `encode(to:)` (`encode` vs `encodeIfPresent`).
 */
object ContactConfigSerializer : KSerializer<MeshCoreNodeConfig.ContactConfig> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ContactConfig")

    override fun serialize(encoder: Encoder, value: MeshCoreNodeConfig.ContactConfig) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("ContactConfig is JSON-only")
        val obj = buildJsonObject {
            put("type", JsonPrimitive(value.type.toInt()))
            put("name", JsonPrimitive(value.name))
            put("custom_name", value.customName?.let { JsonPrimitive(it) } ?: JsonNull)
            put("public_key", JsonPrimitive(value.publicKey))
            put("flags", JsonPrimitive(value.flags.toInt()))
            put("latitude", JsonPrimitive(value.latitude))
            put("longitude", JsonPrimitive(value.longitude))
            put("last_advert", JsonPrimitive(value.lastAdvert.toLong()))
            put("last_modified", JsonPrimitive(value.lastModified.toLong()))
            put("out_path", value.outPath?.let { JsonPrimitive(it) } ?: JsonNull)
            value.pathHashMode?.let { put("path_hash_mode", JsonPrimitive(it.toInt())) }
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): MeshCoreNodeConfig.ContactConfig {
        val jsonDecoder = decoder as? JsonDecoder ?: error("ContactConfig is JSON-only")
        val obj: JsonObject = jsonDecoder.decodeJsonElement().jsonObject
        return MeshCoreNodeConfig.ContactConfig(
            type = obj.getValue("type").jsonPrimitive.int.toUByte(),
            name = obj.getValue("name").jsonPrimitive.content,
            customName = obj["custom_name"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            publicKey = obj.getValue("public_key").jsonPrimitive.content,
            flags = obj.getValue("flags").jsonPrimitive.int.toUByte(),
            latitude = obj.getValue("latitude").jsonPrimitive.content,
            longitude = obj.getValue("longitude").jsonPrimitive.content,
            lastAdvert = obj.getValue("last_advert").jsonPrimitive.long.toUInt(),
            lastModified = obj.getValue("last_modified").jsonPrimitive.long.toUInt(),
            outPath = obj["out_path"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            pathHashMode = obj["path_hash_mode"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int?.toUByte(),
        )
    }
}
