package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.chat.pathdetail.PathHop

/**
 * Out-path hop accessors on [ContactDto], ported from iOS `ContactDTO.pathHops` / `pathString`.
 * Uses [PathEncoding] so hop size matches the multibyte path-length codec.
 */

/** Hash size (1–3) encoded in `outPathLength`, or 1 when flood/undecodable. */
val ContactDto.pathHashSize: Int get() = decodedPathHashSize(outPathLength)

/** Total path payload bytes (`hopCount * hashSize`), or 0 when flood/undecodable. */
val ContactDto.pathByteLength: Int get() = decodedPathBytes(outPathLength)

/** Each hop as raw prefix bytes + uppercase hex (empty when flood-routed or empty path). */
val ContactDto.pathHops: List<PathHop> get() = decodedPathHops(outPathLength, outPath)

/** Arrow-joined hop hex, e.g. `A3 → 7F → 42` (empty when no hops). */
val ContactDto.pathString: String
    get() = pathHops.joinToString(separator = PATH_ARROW) { it.hex }

/**
 * Arrow-joined hops with each hop named where [resolveName] recognises its hash prefix, falling back to
 * hex for an unknown or ambiguous one. Mirrors the iOS `pathDisplayWithNames`, which is what turns a
 * route from `A3 → 7F → 42` into the repeater names a responder can act on.
 */
fun ContactDto.pathString(resolveName: (ByteArray) -> String?): String =
    pathHops.joinToString(separator = PATH_ARROW) { resolveName(it.raw) ?: it.hex }

private const val PATH_ARROW = " \u2192 "

/** True when [outPathLength] is the flood-routing sentinel. */
internal fun floodPath(outPathLength: Int): Boolean = outPathLength == CONTACT_FLOOD_PATH_SENTINEL

/** Hop count encoded in [outPathLength], or 0 when flood-routed or undecodable. */
internal fun decodedPathHopCount(outPathLength: Int): Int = PathEncoding.decodePathLen(outPathLength)?.hopCount ?: 0

/** Bytes per hop encoded in [outPathLength], or 1 when flood-routed or undecodable. */
internal fun decodedPathHashSize(outPathLength: Int): Int = PathEncoding.decodePathLen(outPathLength)?.hashSize ?: 1

/** Path payload bytes encoded in [outPathLength], or 0 when flood-routed or undecodable. */
internal fun decodedPathBytes(outPathLength: Int): Int = PathEncoding.decodePathLen(outPathLength)?.byteLength ?: 0

/** Hop chunks for [outPath], empty when the length is flood-routed or carries no payload. */
internal fun decodedPathHops(outPathLength: Int, outPath: ByteArray): List<PathHop> {
    val byteLength = decodedPathBytes(outPathLength)
    if (floodPath(outPathLength) || byteLength <= 0) return emptyList()
    val usable = outPath.copyOf(minOf(byteLength, outPath.size))
    return PathEncoding.pathHops(usable, decodedPathHashSize(outPathLength))
}
