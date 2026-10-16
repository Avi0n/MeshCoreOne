package one.meshcore.shared.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi

/**
 * A model UUID. The Swift producer encodes UUIDs as uppercase hyphenated strings
 * (`A0000000-0000-0000-0000-000000000001`), so the canonical in-memory form is uppercase.
 * Decoding accepts any case and normalizes to uppercase via [normalized]; the same normalization
 * backs the single Room `TypeConverter`, so persistence and the wire agree on one representation.
 */
@Serializable(with = UuidSerializer::class)
@JvmInline
value class Uuid(val value: String) {
    companion object {
        /** Builds a [Uuid] from any-case input, normalizing to the uppercase canonical form. */
        fun normalized(raw: String): Uuid = Uuid(raw.uppercase())

        /** Formats exactly 16 bytes in order, preserving all bits and using the model's uppercase form. */
        @OptIn(ExperimentalUuidApi::class)
        internal fun fromBytes(bytes: ByteArray): Uuid = normalized(kotlin.uuid.Uuid.fromByteArray(bytes).toString())

        /** A fresh uppercase UUID. */
        @OptIn(ExperimentalUuidApi::class)
        fun random(): Uuid = normalized(kotlin.uuid.Uuid.random().toString())
    }
}

/**
 * Serializes [Uuid] as a JSON string, uppercasing on encode (matching Swift's default `UUID` strategy)
 * and accepting any case on decode. Cross-platform backup encoding relies on the uppercasing here.
 */
object UuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Uuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uuid) = encoder.encodeString(value.value.uppercase())

    override fun deserialize(decoder: Decoder): Uuid = Uuid.normalized(decoder.decodeString())
}
