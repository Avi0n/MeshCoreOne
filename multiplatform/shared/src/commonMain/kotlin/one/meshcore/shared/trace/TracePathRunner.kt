package one.meshcore.shared.trace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import one.meshcore.shared.meshcore.FirmwareSuggestedTimeout
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.TraceNode
import one.meshcore.shared.model.decodeHex
import kotlin.random.Random

/**
 * Parses a comma-separated uppercase/lowercase hex hop path into outbound [PathHop]s of uniform
 * [hashSize] width. Returns `null` when empty, mixed width, non-hex, or width ≠ [hashSize].
 */
fun parseTracePathHex(input: String, hashSize: Int): List<PathHop>? {
    if (hashSize !in VALID_TRACE_HASH_SIZES) return null
    val tokens = input.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    val expectedLen = hashSize * HEX_CHARS_PER_BYTE
    val hops = tokens.mapNotNull { token ->
        if (token.length != expectedLen || !token.all { it.isHexDigit() }) {
            null
        } else {
            PathHop(hashBytes = requireNotNull(decodeHex(token)))
        }
    }
    return hops.takeIf { it.size == tokens.size }
}

/**
 * Builds display hops from a firmware [TraceInfo] using the receiver-attribution model (iOS
 * TracePathViewModel): start node (no SNR) → intermediate path nodes with SNR → end node with last SNR.
 */
fun buildTraceResultHops(
    path: List<TraceNode>,
    deviceName: String,
    nameForHash: (ByteArray) -> String? = { null },
): List<TraceHop> {
    val hops = mutableListOf<TraceHop>()
    hops += TraceHop(
        hashBytes = null,
        resolvedName = deviceName,
        snr = 0.0,
        isStartNode = true,
        isEndNode = false,
    )
    for (node in path) {
        val hash = node.hashBytes ?: continue
        hops += TraceHop(
            hashBytes = hash,
            resolvedName = nameForHash(hash),
            snr = node.snr,
            isStartNode = false,
            isEndNode = false,
        )
    }
    val endSnr = path.lastOrNull()?.snr ?: 0.0
    hops += TraceHop(
        hashBytes = null,
        resolvedName = deviceName,
        snr = endSnr,
        isStartNode = false,
        isEndNode = true,
    )
    return hops
}

/** Flood-profile sanitize of the firmware-suggested timeout, through the one shared policy. */
fun sanitizedFloodTimeoutMs(suggestedTimeoutMs: Long): Long =
    FirmwareSuggestedTimeout.sanitizedMs(suggestedTimeoutMs, FirmwareSuggestedTimeout.Profile.FLOOD)

/**
 * One path trace: the bytes to walk, the hash width, and the callbacks that send and name hops.
 * [TracePathRequest.flags] is the effective trace mode (0/1/2 → 1/2/4-byte hashes).
 */
data class TracePathRequest(
    val pathBytes: ByteArray,
    val hashSize: Int,
    val flags: Int,
    val events: Flow<MeshEvent>,
    val sendTrace: suspend (tag: Long, flags: Int, path: ByteArray) -> MessageSentInfo,
    val deviceName: String,
    val failureMessages: TraceFailureMessages,
    val nowMs: () -> Long,
    val nameForHash: (ByteArray) -> String? = { null },
    val randomTag: () -> Long = { Random.nextLong(0, UInt.MAX_VALUE.toLong()) },
)

/**
 * Sends one path trace and waits for a matching [MeshEvent.TraceData]. Subscribe-before-send (same
 * race fix as zero-hop ping).
 */
suspend fun runTracePath(request: TracePathRequest): TraceResult {
    if (request.pathBytes.isEmpty()) {
        return TraceResult.failure(request.failureMessages.noResponse, request.pathBytes, request.hashSize)
    }
    return try {
        TracePathWaiter(TracePathWaiter.Dependencies(events = { request.events }, radioId = { null })).run(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TraceResult.failure(request.failureMessages.sendFailed, request.pathBytes, request.hashSize)
    }
}

/**
 * What a failed trace tells the user. Split because "no response" and "the packet never went out" are
 * different failures with different remedies, and iOS reports them separately.
 */
data class TraceFailureMessages(val noResponse: String, val sendFailed: String)

private const val HEX_CHARS_PER_BYTE = 2
