package one.meshcore.shared.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Serializes `ByteArray` as a standard, padded Base64 string. This is mandatory: kotlinx's built-in
 * `ByteArray` serializer emits a JSON array of byte integers, whereas the Swift producer emits Base64
 * for every `Data` field (public keys, channel secrets, path bytes). Applied file-wide on the DTOs
 * via `@file:UseSerializers`.
 */
@OptIn(ExperimentalEncodingApi::class)
object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.encode(value))

    override fun deserialize(decoder: Decoder): ByteArray = Base64.decode(decoder.decodeString())
}

/**
 * Base for enum serializers pinned to an explicit integer raw value rather than name or ordinal.
 * The Swift enums encode as their `Int`/`UInt8` raw on the wire, so decode reads the raw and maps it
 * back; an unrecognized raw fails loudly rather than silently defaulting.
 */
abstract class IntEnumSerializer<T : Enum<T>>(
    serialName: String,
    private val fromRaw: (Int) -> T?,
    private val toRaw: (T) -> Int,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeInt(toRaw(value))

    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeInt()
        return fromRaw(raw) ?: throw SerializationException("Unknown $descriptor raw value: $raw")
    }
}
