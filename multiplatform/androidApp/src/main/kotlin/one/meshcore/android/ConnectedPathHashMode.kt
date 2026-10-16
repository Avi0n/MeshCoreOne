package one.meshcore.android

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.ZeroHopPingClock
import one.meshcore.shared.meshcore.MeshSession
import java.util.concurrent.atomic.AtomicReference
import one.meshcore.shared.contact.zeroHopPing as performZeroHopPing

/** The width both hop encodings fall back to when no radio is connected: mode 0, one byte per hop. */
private const val DEFAULT_PATH_HASH_MODE = 0

/**
 * The connected radio's routing path-hash mode, and the two hop widths derived from it. Routing
 * (out-path) hops are `mode + 1` bytes; trace hops are `1 shl mode`. A radio configured for 2- or
 * 4-byte hashes mis-parses anything narrower against the declared path length, so both the path editor
 * and the zero-hop ping read this one value rather than assuming a byte. Mirrors iOS `Device.hashSize`
 * and `Device.traceHashSize`.
 */
internal class ConnectedPathHashMode {
    private val mode = AtomicReference<Int?>(null)

    fun set(value: Int?) = mode.set(value)

    /**
     * Persist a verified radio write, then refresh this cache so path editing and zero-hop ping
     * encode at the new width. Settings and Tools CLI must both go through here.
     */
    suspend fun applyVerifiedWrite(mode: Int, persist: suspend (Int) -> Unit) {
        persist(mode)
        set(mode)
    }

    /** Routing hop width in bytes, or `null` when disconnected (the editor then clamps to one byte). */
    fun routingHopBytes(): Int? = mode.get()?.let { one.meshcore.shared.trace.routingHashSize(it) }

    /** The trace hash mode for a zero-hop ping; mode 0 when disconnected, matching iOS `?? 0`. */
    fun traceHashMode(): Int = mode.get() ?: DEFAULT_PATH_HASH_MODE
}

/**
 * Zero-hop contact ping over [session] at the radio's configured [pathHashMode]. The trace hop width and
 * the declared flags both come from that one mode, so a radio on 2- or 4-byte hashes is never probed
 * with a truncated key prefix. Mirrors iOS `PingHelper.zeroHopPing`.
 */
internal suspend fun contactZeroHopPing(
    contact: ContactDto,
    session: MeshSession,
    pathHashMode: Int,
    nowMs: () -> Long,
    onHeard: suspend () -> Unit = {},
): ContactPingResult = performZeroHopPing(
    publicKey = contact.publicKey,
    pathHashMode = pathHashMode,
    events = session.events,
    sendTrace = { tag, flags, path ->
        session.sendTrace(tag = tag, authCode = null, flags = flags, path = path)
    },
    clock = ZeroHopPingClock(nowMs = nowMs, onHeard = onHeard),
)
