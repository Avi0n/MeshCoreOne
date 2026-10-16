package one.meshcore.shared.meshcore

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * The `contact` envelope a whole-contact command carries. `addContact`, `changeContactFlags`, and
 * `requestRegions` take a byte-for-byte `contact` envelope (the same shape a `contact` event
 * carries) rather than a wide argument list; the Swift `ContactDecoder` is its exact inverse. Key
 * order is irrelevant to the Swift decoder, so a plain kotlinx encode suffices — this is not part of
 * the byte-identical freeze.
 */
@Serializable
private data class ContactEnvelope(val v: Int, val kind: String, val payload: MeshContact)

/** Encodes a [MeshContact] as the UTF-8 bytes of a v1 `contact` envelope for a whole-contact command. */
internal fun encodeContactEnvelope(contact: MeshContact): ByteArray =
    bridgeJson.encodeToString(ContactEnvelope(BRIDGE_ENVELOPE_VERSION, "contact", contact)).encodeToByteArray()
