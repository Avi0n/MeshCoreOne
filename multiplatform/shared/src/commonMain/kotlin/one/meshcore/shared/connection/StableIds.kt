package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid
import kotlin.uuid.ExperimentalUuidApi

/**
 * Deterministic row-id derivation for values that have a stable natural key but no UUID. The connection
 * layer uses it to map an Android BLE handle to the same Device row across reconnects; the original
 * handle is stored separately because this mapping is intentionally non-reversible.
 */
internal object StableIds {
    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L
    private const val BYTE_MASK = 0xFFL

    /**
     * A canonical UUID deterministically derived from [seed]. Two independent 64-bit FNV-1a passes
     * (forward and reversed) fill the 128 bits, so distinct seeds map to distinct ids with negligible
     * collision risk for the stub's scale.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun uuid(seed: ByteArray): Uuid {
        val high = fnv1a(seed, forward = true)
        val low = fnv1a(seed, forward = false)
        return Uuid.normalized(kotlin.uuid.Uuid.fromLongs(high, low).toString())
    }

    private fun fnv1a(bytes: ByteArray, forward: Boolean): Long {
        var hash = FNV_OFFSET_BASIS
        val indices = if (forward) bytes.indices else bytes.indices.reversed()
        for (i in indices) {
            hash = hash xor (bytes[i].toLong() and BYTE_MASK)
            hash *= FNV_PRIME
        }
        return hash
    }
}
