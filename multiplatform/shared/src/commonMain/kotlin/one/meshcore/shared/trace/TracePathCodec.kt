package one.meshcore.shared.trace

import one.meshcore.shared.model.toUppercaseHex

/**
 * Pure byte-layout logic for a trace path: building the on-wire buffer from an outbound hop list,
 * reconstructing the outbound half from a saved full path, uniform-width rebuilds when the hash size
 * changes, and the chunked display string. Mirrors the corresponding Swift `TracePathViewModel`
 * computed properties and helpers.
 */

/**
 * The full on-wire path buffer: the outbound hop hashes flattened, optionally followed by the mirrored
 * return path. Auto-return drops the turnaround (last) hop to avoid duplicating it, so outbound
 * `[A, B, C]` becomes `A B C B A`. Empty outbound yields an empty buffer. Mirrors Swift `fullPathData`.
 */
fun buildFullPathBytes(outbound: List<PathHop>, autoReturnPath: Boolean): ByteArray {
    if (outbound.isEmpty()) return ByteArray(0)
    val forward = outbound.map { it.hashBytes }
    val ordered = if (autoReturnPath) forward + forward.asReversed().drop(1) else forward
    return ordered.flattenBytes()
}

/**
 * Rebuild every hop to a uniform [hashSize] width. A hop with a public key re-prefixes exactly from it;
 * a key-less hop widens from its existing hash bytes and zero-pads if short. Uniform width is mandatory
 * because the trace flags byte declares one hash size for the whole path. Mirrors Swift `setTraceHashMode`.
 */
fun rebuildHopsToWidth(hops: List<PathHop>, hashSize: Int): List<PathHop> = hops.map { hop ->
    hop.copy(hashBytes = widenedHashBytes(hop.publicKey ?: hop.hashBytes, hashSize))
}

/** Zero-pads or truncates [source] to [hashSize]. A short key keeps the leading bytes. */
internal fun widenedHashBytes(source: ByteArray, hashSize: Int): ByteArray {
    val widened = ByteArray(hashSize)
    source.copyInto(widened, endIndex = minOf(hashSize, source.size))
    return widened
}

/**
 * Number of outbound hops encoded in a saved full path of [totalBytes] bytes at [hashSize] bytes per
 * hop. The full path is `outbound + mirrored return (minus turnaround)`, so the outbound half is the
 * total hop count rounded up: `(totalHops + 1) / 2`. Mirrors Swift `loadSavedPath` reconstruction.
 */
fun outboundHopCount(totalBytes: Int, hashSize: Int): Int {
    if (hashSize <= 0) return 0
    val totalHops = totalBytes / hashSize
    return (totalHops + 1) / 2
}

/** Byte length of the outbound half of a saved full path. See [outboundHopCount]. */
fun outboundByteCount(totalBytes: Int, hashSize: Int): Int = outboundHopCount(totalBytes, hashSize) * hashSize

/**
 * The outbound hop hashes sliced from a saved full path, each [hashSize] bytes wide. Callers resolve
 * each slice back to a contact to rebuild [PathHop]s. Mirrors the slicing loop in Swift `loadSavedPath`.
 */
fun outboundHashSlices(fullPath: ByteArray, hashSize: Int): List<ByteArray> {
    if (fullPath.isEmpty() || hashSize <= 0) return emptyList()
    val limit = minOf(outboundByteCount(fullPath.size, hashSize), fullPath.size)
    val slices = mutableListOf<ByteArray>()
    var start = 0
    while (start < limit) {
        val end = minOf(start + hashSize, fullPath.size)
        slices += fullPath.copyOfRange(start, end)
        start += hashSize
    }
    return slices
}

/** Comma-separated uppercase-hex of [bytes], one group per [hashSize]-byte chunk. Mirrors `tracedPathString`. */
fun chunkedHexPath(bytes: ByteArray, hashSize: Int): String {
    if (bytes.isEmpty() || hashSize <= 0) return ""
    val groups = mutableListOf<String>()
    var start = 0
    while (start < bytes.size) {
        val end = minOf(start + hashSize, bytes.size)
        groups += bytes.copyOfRange(start, end).toUppercaseHex()
        start += hashSize
    }
    return groups.joinToString(separator = ",")
}

private fun List<ByteArray>.flattenBytes(): ByteArray {
    val total = sumOf { it.size }
    val out = ByteArray(total)
    var offset = 0
    for (chunk in this) {
        chunk.copyInto(out, offset)
        offset += chunk.size
    }
    return out
}
