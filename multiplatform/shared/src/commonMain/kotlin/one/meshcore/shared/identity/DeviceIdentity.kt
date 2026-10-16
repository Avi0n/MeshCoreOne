package one.meshcore.shared.identity

import one.meshcore.shared.crypto.sha256
import one.meshcore.shared.model.Uuid

/**
 * Derives the stable `Device` row id for a radio that has no Bluetooth handle. Mirrors Swift
 * `DeviceIdentity.deriveUUID(from:)`: the first 16 bytes of `SHA-256(publicKey)` become the UUID
 * verbatim (no version/variant bits are forced), so a WiFi radio maps to the same `Device` row across
 * reconnects and app launches even though its TCP host/port can change.
 *
 * This is only the row id (the `id` axis). The data-partition key (`radioID`) is resolved separately by
 * matching the persisted public key, and is minted exactly once per identity — the two never collapse.
 */
object DeviceIdentity {
    fun deriveUuid(publicKey: ByteArray): Uuid = Uuid.fromBytes(sha256(publicKey).copyOfRange(0, UUID_BYTES))

    private const val UUID_BYTES = 16
}
