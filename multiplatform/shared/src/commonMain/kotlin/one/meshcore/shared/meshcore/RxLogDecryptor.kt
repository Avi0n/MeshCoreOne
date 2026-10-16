package one.meshcore.shared.meshcore

/**
 * A candidate key and the caller's own identifier for it: a channel slot index for a channel row, a
 * position in the candidate list for a direct-message row. The identifier comes straight back on a
 * match, so the caller never re-derives which key worked.
 */
class RxLogDecryptKey(val index: Int, val bytes: ByteArray)

/**
 * One RX-log row handed down for decryption. [payload] is the packet payload verbatim, framing byte
 * included: the wire layout is the protocol implementation's business, not this side's.
 */
sealed interface RxLogDecryptRow {
    val payload: ByteArray

    /** Candidate keys, tried in order until one authenticates the row. */
    val keys: List<RxLogDecryptKey>

    /** A group packet, tried against each known channel secret. */
    class Channel(override val payload: ByteArray, override val keys: List<RxLogDecryptKey>) : RxLogDecryptRow

    /** A direct message, tried against each candidate sender key under this device's [privateKey]. */
    class Direct(
        override val payload: ByteArray,
        val privateKey: ByteArray,
        override val keys: List<RxLogDecryptKey>,
    ) : RxLogDecryptRow
}

/** One row's outcome, in the same order the rows were submitted. */
sealed interface RxLogDecryptResult {
    /** [keyIndex] is the [RxLogDecryptKey.index] that opened the row; [text] is null for invalid UTF-8. */
    class Success(val keyIndex: Int, val senderTimestamp: Long, val text: String?) : RxLogDecryptResult

    /** Every candidate key was tried and none authenticated the row. */
    data object NoMatchingKey : RxLogDecryptResult

    /** The payload cannot hold a MAC and one cipher block, so there is nothing to try. */
    data object PayloadTooShort : RxLogDecryptResult

    /** No protocol implementation answered, so the row's plaintext is still unknown. */
    class Unavailable(val reason: String) : RxLogDecryptResult
}

/**
 * The RX log's window onto MeshCore's message crypto.
 *
 * The log lists raw received packets, so a row is decrypted long after the radio delivered it and
 * again whenever a channel secret, contact key, or the device key arrives. Every such derivation
 * goes through the one protocol implementation below the bridge; nothing above it decrypts.
 *
 * Rows are submitted as a batch because a late secret re-derives the whole held log at once, and
 * one boundary crossing for the batch beats one per row.
 */
fun interface RxLogDecryptor {
    fun decrypt(rows: List<RxLogDecryptRow>): List<RxLogDecryptResult>
}

private const val NO_BRIDGE_REASON = "this host ships no MeshCore bridge"

internal object UnavailableRxLogDecryptor : RxLogDecryptor {
    override fun decrypt(rows: List<RxLogDecryptRow>): List<RxLogDecryptResult> =
        rows.map { RxLogDecryptResult.Unavailable(NO_BRIDGE_REASON) }
}

/** Forwards to the decryptor installed on [PlatformBridge], which a session may set after this cache exists. */
private object LiveRxLogDecryptor : RxLogDecryptor {
    override fun decrypt(rows: List<RxLogDecryptRow>): List<RxLogDecryptResult> =
        PlatformBridge.rxLogDecryptor.decrypt(rows)
}

/** The decryptor this platform can reach. Hosts without a bridge report every row unavailable. */
fun platformRxLogDecryptor(): RxLogDecryptor = LiveRxLogDecryptor
