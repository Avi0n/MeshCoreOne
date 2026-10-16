package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.model.toUppercaseHex

/**
 * Routing path-length lookup. The byte layout lives in Swift `decodePathLen` / `encodePathLen`.
 * [PATH_LEN_HASH_SIZE] is that function for every byte, so UI rows, restored messages, and path
 * editing do not reinterpret the byte and do not call JNI.
 *
 * The width is the linear routing width, distinct from the power-of-2 trace width in
 * [one.meshcore.shared.trace.routingHashSize].
 */
object PathEncoding {
    /** Highest valid hash-size mode (0 → 1-byte, 1 → 2-byte, 2 → 3-byte; mode 3 is reserved). */
    const val MAX_PATH_HASH_MODE: Int = 2

    /** Highest hop count an encoded path-length byte can hold. */
    const val MAX_HOP_COUNT: Int = 63

    /** Maximum encoded out-path length in bytes (firmware `MAX_PATH_SIZE`). */
    const val MAX_PATH_BYTES: Int = 64

    /** Mode number for a path-length byte whose hash size the table marks reserved. */
    const val RESERVED_HASH_MODE: Int = 3

    private const val BYTE_MASK = 0xFF
    private const val MIN_HASH_SIZE = 1
    private const val MAX_HASH_SIZE = 3
    private const val RESERVED_HASH_SIZE = 0

    /**
     * Decodes an encoded path-length byte into its [PathLenDecoded] components, or null when Swift
     * reports the reserved hash mode. [encoded] is treated as an unsigned byte (0-255).
     */
    fun decodePathLen(encoded: Int): PathLenDecoded? {
        val index = encoded and BYTE_MASK
        val hashSize = PATH_LEN_HASH_SIZE[index]
        if (hashSize == RESERVED_HASH_SIZE) return null
        return PathLenDecoded(
            hashSize = hashSize,
            hopCount = PATH_LEN_HOP_COUNT[index],
            byteLength = PATH_LEN_BYTE_LENGTH[index],
        )
    }

    /**
     * Encodes [hashSize] (1, 2, or 3 bytes per hop) and [hopCount] into the path-length byte Swift
     * produces for that pair. The hop count is clamped to [MAX_HOP_COUNT].
     */
    fun encodePathLen(hashSize: Int, hopCount: Int): Int {
        require(hashSize in MIN_HASH_SIZE..MAX_HASH_SIZE) { "hashSize must be 1, 2, or 3" }
        val hops = minOf(hopCount, MAX_HOP_COUNT)
        val encoded = PATH_LEN_ENCODED[(hashSize - MIN_HASH_SIZE) * (MAX_HOP_COUNT + 1) + hops]
        check(encoded >= 0) { "no path length byte for hashSize=$hashSize hopCount=$hops" }
        return encoded
    }

    /**
     * Hash-size mode for a stored path-length byte. A decoded hash size maps to `hashSize - 1`.
     * A reserved byte maps to [RESERVED_HASH_MODE].
     */
    fun pathHashMode(encoded: Int): Int {
        val hashSize = PATH_LEN_HASH_SIZE[encoded and BYTE_MASK]
        return if (hashSize == RESERVED_HASH_SIZE) RESERVED_HASH_MODE else hashSize - MIN_HASH_SIZE
    }

    /**
     * Splits path bytes into per-hop [PathHop] chunks of [hashSize] bytes each, carrying the raw slice and
     * its uppercase hex. A non-positive [hashSize] returns no hops (avoiding a zero-stride trap); a trailing
     * partial chunk shorter than [hashSize] is emitted as-is. Mirrors the Swift `Data.pathHops(hashSize:)`.
     */
    fun pathHops(bytes: ByteArray, hashSize: Int): List<PathHop> {
        if (hashSize <= 0) return emptyList()
        val hops = mutableListOf<PathHop>()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + hashSize, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            hops += PathHop(raw = chunk, hex = chunk.toUppercaseHex())
            offset += hashSize
        }
        return hops
    }
}

/** Decoded components of a path-length byte: bytes per hop, hop count, and total path byte length. */
data class PathLenDecoded(val hashSize: Int, val hopCount: Int, val byteLength: Int)

/** One hop of a routing path: its raw hash-prefix bytes and their uppercase-hex rendering. */
class PathHop(val raw: ByteArray, val hex: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PathHop && raw.contentEquals(other.raw) && hex == other.hex)

    override fun hashCode(): Int = 31 * raw.contentHashCode() + hex.hashCode()

    override fun toString(): String = "PathHop($hex)"
}
