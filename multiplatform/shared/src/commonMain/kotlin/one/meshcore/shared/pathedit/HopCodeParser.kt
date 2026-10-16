package one.meshcore.shared.pathedit

import one.meshcore.shared.model.decodeHex

/** A node a hash prefix resolved to: its full public key and optional display name. */
class ResolvedNode(val publicKey: ByteArray, val name: String?)

/**
 * Pure parser for the comma-separated hex-code bulk-add entry shared by the contact path editor and the
 * trace path builder, ported from the iOS `HopCodeParser`. The single source of truth for both the
 * bulk-add preview and the actual add, so the panel can never show a status that differs from what
 * tapping "Add" produces.
 */
object HopCodeParser {
    private const val HEX_RADIX = 16

    /**
     * Classify each code in [input] without mutating any path.
     *
     * @param hashSize bytes per hop; a code must be exactly `hashSize * 2` hex digits.
     * @param existingHashes hash-byte prefixes already in the path (for [HopCodeStatus.AlreadyInPath]),
     *   keyed as `List<Byte>` so byte content — not array identity — decides membership.
     * @param remainingCapacity hops still addable, or `null` when unlimited; resolvable codes beyond it
     *   become [HopCodeStatus.PathFull].
     * @param resolve maps a hash prefix to a node's full public key and display name, or `null` when no
     *   node matches.
     */
    fun classify(
        input: String,
        hashSize: Int,
        existingHashes: Set<List<Byte>>,
        remainingCapacity: Int?,
        resolve: (ByteArray) -> ResolvedNode?,
    ): List<HopCodeClassification> {
        val codes = input.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

        val seen = mutableSetOf<String>()
        val uniqueCodes = codes.filter { seen.add(it) }

        val pathHashes = existingHashes.toMutableSet()
        var added = 0

        return uniqueCodes.map { code ->
            val hashData = parseHex(code, hashSize)
                ?: return@map HopCodeClassification(code, HopCodeStatus.InvalidFormat)
            val hashKey = hashData.toList()
            if (pathHashes.contains(hashKey)) {
                return@map HopCodeClassification(code, HopCodeStatus.AlreadyInPath)
            }
            val resolved = resolve(hashData)
                ?: return@map HopCodeClassification(code, HopCodeStatus.NotFound)
            if (remainingCapacity != null && added >= remainingCapacity) {
                return@map HopCodeClassification(code, HopCodeStatus.PathFull)
            }
            added += 1
            pathHashes.add(hashKey)
            val hop = PathHop(hashBytes = hashData, publicKey = resolved.publicKey, resolvedName = resolved.name)
            HopCodeClassification(code, HopCodeStatus.WillAdd(hop))
        }
    }

    /** Parse a hex [code] into exactly [hashSize] bytes, or `null` if malformed (wrong length or non-hex). */
    fun parseHex(code: String, hashSize: Int): ByteArray? {
        if (code.length != hashSize * 2) return null
        if (!code.all { it.digitToIntOrNull(HEX_RADIX) != null }) return null
        val bytes = decodeHex(code) ?: return null
        return if (bytes.size == hashSize) bytes else null
    }
}
