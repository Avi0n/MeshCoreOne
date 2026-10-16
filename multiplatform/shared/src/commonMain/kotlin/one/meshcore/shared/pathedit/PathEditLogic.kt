package one.meshcore.shared.pathedit

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.model.prefixBytes

/**
 * Why a candidate path can't be safely saved, ported from the two error branches the iOS
 * `PathManagementViewModel.saveRejection` returns as localized strings. Kept as a typed reason so this
 * pure logic stays free of the string system; the view model maps each case to the
 * `PathManagement.Error.*` L10n key it surfaces through `errorMessage`.
 */
sealed interface PathSaveRejection {
    /**
     * A hop stored at a narrower hash size has no full public key to widen from, so it would serialize
     * short (no padding) and firmware would mis-parse the path against the declared length. Maps to
     * `PathManagement.Error.hopResizeRequired`.
     */
    data object HopResizeRequired : PathSaveRejection

    /** More hops than the current hash size's budget allows. Maps to `PathManagement.Error.tooManyHops`. */
    data class TooManyHops(val maxHopCount: Int) : PathSaveRejection
}

/** The re-encoded path payload plus the packed `outPathLength` byte (0-255). */
class EncodedPath(val path: ByteArray, val length: Int)

/**
 * The pure path-editing statics ported from `PathManagementViewModel`: load-time hash-size
 * normalization, the save guard, and the re-encode. All take an explicit `targetHashSize` (the device's
 * clamped current hash size) so tests drive every branch without a live connection. The wire packing
 * itself is delegated to the already-bridged [PathEncoding].
 */
object PathEditLogic {
    private const val MIN_HASH_SIZE = 1
    private const val MAX_HASH_SIZE = 3

    /**
     * Normalize a hop loaded from storage so its `hashBytes` width matches the device's current hash
     * size. Three branches, mirroring iOS `normalizeHop`:
     *
     * 1. Resolved (`publicKey != null`) → re-slice from the full key at [targetHashSize]. Idempotent
     *    when already at the target width.
     * 2. Unresolved and wider than target → truncate the stored `hashBytes`.
     * 3. Unresolved and same width or narrower → leave as-is. Equal width is already consistent; a
     *    narrower unresolved hop cannot be safely widened (you can't invent prefix bytes), so it is left
     *    short deliberately so [saveRejection] blocks the save.
     *
     * `take(n)` (like Swift `Data.prefix(n)`) returns `min(count, n)` bytes with no padding — that is
     * what leaves branch-3 hops short rather than zero-padded.
     */
    fun normalizeHop(hop: PathHop, targetHashSize: Int): PathHop {
        require(targetHashSize in MIN_HASH_SIZE..MAX_HASH_SIZE) { "targetHashSize must be 1, 2, or 3" }
        val publicKey = hop.publicKey
        return when {
            publicKey != null ->
                PathHop(publicKey.prefixBytes(targetHashSize), publicKey, hop.resolvedName)
            hop.hashBytes.size > targetHashSize ->
                PathHop(hop.hashBytes.prefixBytes(targetHashSize), null, hop.resolvedName)
            else -> hop
        }
    }

    /**
     * The reason [hops] can't be safely encoded at [targetHashSize], or null when encoding is safe.
     * Mirrors iOS `saveRejection`: an unresolved hop narrower than the target can't widen (would
     * serialize short), and the hop count must not exceed [maxHopCount].
     */
    fun saveRejection(hops: List<PathHop>, targetHashSize: Int, maxHopCount: Int): PathSaveRejection? {
        require(targetHashSize in MIN_HASH_SIZE..MAX_HASH_SIZE) { "targetHashSize must be 1, 2, or 3" }
        val hopNeedsResize = hops.any { it.publicKey == null && it.hashBytes.size < targetHashSize }
        return when {
            hopNeedsResize -> PathSaveRejection.HopResizeRequired
            hops.size > maxHopCount -> PathSaveRejection.TooManyHops(maxHopCount)
            else -> null
        }
    }

    /**
     * Encode the path payload at [targetHashSize], preferring each hop's full public key and falling back
     * to its stored `hashBytes`. Callers must have passed [saveRejection] first so a too-narrow unresolved
     * hop can't reach here and truncate below the declared width. Mirrors iOS `encodeEditablePath`.
     */
    fun encodeEditablePath(hops: List<PathHop>, targetHashSize: Int): EncodedPath {
        require(targetHashSize in MIN_HASH_SIZE..MAX_HASH_SIZE) { "targetHashSize must be 1, 2, or 3" }
        val path = hops.flatMap { hop ->
            (hop.publicKey ?: hop.hashBytes).prefixBytes(targetHashSize).asList()
        }.toByteArray()
        val length = PathEncoding.encodePathLen(hashSize = targetHashSize, hopCount = hops.size)
        return EncodedPath(path = path, length = length)
    }
}
