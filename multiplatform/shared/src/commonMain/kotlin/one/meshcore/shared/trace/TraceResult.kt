package one.meshcore.shared.trace

/**
 * The outcome of a single trace run: the per-hop rows, round-trip duration, and the exact path bytes
 * that were traced (chunked by [hashSize] for display). Mirrors the Swift `TraceResult`.
 *
 * [errorMessage] carries the already-localized failure text for a timeout or send failure; success
 * results leave it `null`. The localization lookup lives in the UI layer, so the factories here take a
 * caller-supplied message rather than resolving a key.
 */
data class TraceResult(
    val hops: List<TraceHop>,
    val durationMs: Int,
    val success: Boolean,
    val errorMessage: String?,
    val tracedPathBytes: ByteArray,
    val hashSize: Int,
) {
    /** Comma-separated uppercase-hex path for display/copy, chunked by [hashSize]. Mirrors `tracedPathString`. */
    val tracedPathString: String
        get() = chunkedHexPath(tracedPathBytes, hashSize)

    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is TraceResult &&
                hops == other.hops &&
                durationMs == other.durationMs &&
                success == other.success &&
                errorMessage == other.errorMessage &&
                tracedPathBytes.contentEquals(other.tracedPathBytes) &&
                hashSize == other.hashSize
            )

    override fun hashCode(): Int {
        var result = hops.hashCode()
        result = 31 * result + durationMs
        result = 31 * result + success.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + tracedPathBytes.contentHashCode()
        result = 31 * result + hashSize
        return result
    }

    companion object {
        /** A failed result (timeout or send failure) with no hops. [message] is already localized. */
        fun failure(message: String, attemptedPath: ByteArray, hashSize: Int): TraceResult = TraceResult(
            hops = emptyList(),
            durationMs = 0,
            success = false,
            errorMessage = message,
            tracedPathBytes = attemptedPath,
            hashSize = hashSize,
        )
    }
}
