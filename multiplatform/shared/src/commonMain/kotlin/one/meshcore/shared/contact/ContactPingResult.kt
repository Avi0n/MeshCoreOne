package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import one.meshcore.shared.meshcore.FirmwareSuggestedTimeout
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.ParsedRxLogData
import one.meshcore.shared.meshcore.TraceInfo
import one.meshcore.shared.tools.RxLogPayloadType
import one.meshcore.shared.trace.MAX_TRACE_HASH_MODE
import one.meshcore.shared.trace.MIN_TRACE_HASH_MODE
import one.meshcore.shared.trace.traceHashSize
import kotlin.random.Random

/** Outcome of a zero-hop contact ping (iOS `PingResult`). */
sealed class ContactPingResult {
    data class Success(val latencyMs: Int, val snrThere: Double, val snrBack: Double) : ContactPingResult()
    data class Failure(val message: String) : ContactPingResult()

    /** Timeout or a failure with no service text. The row shows `contacts_detail_ping_no_response`. */
    data object NoResponse : ContactPingResult()
}

/** Clock, tag source, and heard callback for one zero-hop ping. */
data class ZeroHopPingClock(
    val nowMs: () -> Long,
    val randomTag: () -> Long = { Random.nextLong(0, UInt.MAX_VALUE.toLong()) },
    val onHeard: suspend () -> Unit = {},
)

/**
 * Sends a zero-hop trace to [publicKey] and waits for a matching response.
 *
 * The hop width and the declared flags both derive from [pathHashMode], so the target prefix can never
 * disagree with the width the radio parses: trace hops are `1 shl mode` bytes wide (iOS
 * `device.traceHashSize` / `device.pathHashMode`).
 *
 * Prefer [MeshEvent.RxLogData] TRACE frames with a matching tag (iOS `PingHelper` /
 * `AdvertisementEvent.traceSnrObserved`); fall back to [MeshEvent.TraceData] so unit tests and
 * firmware paths that only push the trace reply still complete. Subscribe-before-send; timeout from
 * the firmware suggestion clamped to the zero-hop profile.
 */
suspend fun zeroHopPing(
    publicKey: ByteArray,
    pathHashMode: Int,
    events: Flow<MeshEvent>,
    sendTrace: suspend (tag: Long, flags: Int, path: ByteArray) -> MessageSentInfo,
    clock: ZeroHopPingClock,
): ContactPingResult {
    val tag = clock.randomTag()
    val mode = pathHashMode.coerceIn(MIN_TRACE_HASH_MODE, MAX_TRACE_HASH_MODE)
    val pathData = publicKey.copyOf(minOf(traceHashSize(mode), publicKey.size))
    val startMs = clock.nowMs()
    return try {
        coroutineScope {
            // Subscribe before send so a fast radio response cannot arrive ahead of the listener.
            val wait = async {
                events.mapNotNull { event -> snrObservation(event, tag) }.first()
            }
            yield()
            val sent = sendTrace(tag, mode, pathData)
            val timeoutMs = sanitizedZeroHopTimeoutMs(sent.suggestedTimeoutMs)
            val snr = awaitZeroHopSnr(wait, timeoutMs)
            val latencyMs = (clock.nowMs() - startMs).toInt().coerceAtLeast(0)
            stampHeard(clock)
            ContactPingResult.Success(latencyMs = latencyMs, snrThere = snr.there, snrBack = snr.back)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // iOS PingHelper maps every send/wait fault to the no-response copy.
        ContactPingResult.NoResponse
    }
}

private suspend fun stampHeard(clock: ZeroHopPingClock) {
    try {
        clock.onHeard()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // iOS logs a stamp fault and still returns success.
    }
}

private suspend fun awaitZeroHopSnr(wait: Deferred<SnrPair>, timeoutMs: Long): SnrPair =
    withTimeoutOrNull(timeoutMs) { wait.await() } ?: run {
        wait.cancel()
        throw PingTimeoutException()
    }

/**
 * Sanitizes the firmware `suggested_timeout_ms` hint for a zero-hop ping, through the one shared policy.
 * The zero-hop profile adds no grace, because the reply crosses a single link.
 */
fun sanitizedZeroHopTimeoutMs(suggestedTimeoutMs: Long): Long =
    FirmwareSuggestedTimeout.sanitizedMs(suggestedTimeoutMs, FirmwareSuggestedTimeout.Profile.ZERO_HOP)

/**
 * Extracts snrThere/snrBack from a matching zero-hop reply, or null when [event] is unrelated.
 * RX-log TRACE matches iOS tag + local SNR + remote SNR-from-path; TraceData uses the path hop list.
 */
internal fun snrObservation(event: MeshEvent, tag: Long): SnrPair? = when (event) {
    is MeshEvent.RxLogData -> snrFromTraceRxLog(event.data, tag)
    is MeshEvent.TraceData -> if (event.trace.tag == tag) snrPair(event.trace) else null
    else -> null
}

internal data class SnrPair(val there: Double, val back: Double)

/**
 * iOS `AdvertisementService` TRACE RX-log branch: tag is LE u32 at the start of [packetPayload],
 * snrBack is the local SNR, snrThere is the last path-node SNR (Int8/4).
 */
internal fun snrFromTraceRxLog(data: ParsedRxLogData, tag: Long): SnrPair? {
    if (data.payloadType != RxLogPayloadType.TRACE) return null
    val localSnr = data.snr ?: return null
    if (data.packetPayload.size < TRACE_TAG_BYTES) return null
    if (readUInt32LE(data.packetPayload, 0) != tag) return null
    val remoteSnr = data.pathNodes.lastOrNull()?.let { node ->
        node.toByte().toInt().toDouble() / PATH_NODE_SNR_DIVISOR
    } ?: 0.0
    return SnrPair(there = remoteSnr, back = localSnr)
}

private fun snrPair(trace: TraceInfo): SnrPair {
    val hops = trace.path
    if (hops.isEmpty()) return SnrPair(0.0, 0.0)
    val there = hops.first().snr
    val back = if (hops.size > 1) hops.last().snr else hops.first().snr
    return SnrPair(there, back)
}

private class PingTimeoutException : Exception()

private const val TRACE_TAG_BYTES = 4

/** Path-node SNR is an Int8 times 0.25 (MeshCore path encoding). */
private const val PATH_NODE_SNR_DIVISOR = 4.0
