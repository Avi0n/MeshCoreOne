package one.meshcore.shared.trace

/**
 * Aggregates over a batch of trace runs. Every function considers only the successful results, matching
 * the Swift `TracePathViewModel` batch aggregates (`averageRTT`, `hopStats`, `latestHopSNR`, ...), which
 * all read from `successfulResults`.
 */

/** Mean round-trip time in ms over successful runs, or `null` when none succeeded. Mirrors `averageRTT`. */
fun averageRtt(results: List<TraceResult>): Int? {
    val rtts = results.filter { it.success }.map { it.durationMs }
    if (rtts.isEmpty()) return null
    return rtts.sum() / rtts.size
}

/** Minimum round-trip time in ms over successful runs, or `null` when none succeeded. Mirrors `minRTT`. */
fun minRtt(results: List<TraceResult>): Int? = results.filter { it.success }.map { it.durationMs }.minOrNull()

/** Maximum round-trip time in ms over successful runs, or `null` when none succeeded. Mirrors `maxRTT`. */
fun maxRtt(results: List<TraceResult>): Int? = results.filter { it.success }.map { it.durationMs }.maxOrNull()

/** Integer percent of [batchSize] that succeeded, matching iOS `(successCount * 100) / batchSize`. */
fun batchSuccessPercent(successCount: Int, batchSize: Int): Int =
    if (batchSize > 0) (successCount * PERCENT_SCALE) / batchSize else 0

private const val PERCENT_SCALE = 100

/**
 * SNR statistics for the hop at [index] across successful runs, or `null` for the start node (index 0,
 * which transmitted and has no received SNR) or when no successful run reaches that hop. Start-node hops
 * at the index are skipped. Mirrors Swift `hopStats(at:)`.
 */
fun hopStats(results: List<TraceResult>, index: Int): HopSnrStats? {
    if (index <= 0) return null
    val snrValues = results.filter { it.success }.mapNotNull { result ->
        val hop = result.hops.getOrNull(index) ?: return@mapNotNull null
        if (hop.isStartNode) null else hop.snr
    }
    if (snrValues.isEmpty()) return null
    return HopSnrStats(
        avg = snrValues.sum() / snrValues.size,
        min = snrValues.min(),
        max = snrValues.max(),
    )
}

/**
 * The SNR for the hop at [index] from the most recent successful run, or `null` when the latest success
 * has no such hop. Mirrors Swift `latestHopSNR(at:)`.
 */
fun latestHopSnr(results: List<TraceResult>, index: Int): Double? {
    val latest = results.lastOrNull { it.success } ?: return null
    return latest.hops.getOrNull(index)?.snr
}
