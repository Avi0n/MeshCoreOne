package one.meshcore.shared.tools

import one.meshcore.shared.runExceptionFallback

/**
 * The outcome of one polling tick. A failed or unavailable read is a distinct state from "no reading
 * yet": the chart would otherwise freeze silently while the user believes it is live. Mirrors the two
 * error branches of the Swift `NoiseFloorViewModel.fetchReading`.
 */
sealed interface NoiseFloorPoll {
    data class Sample(val reading: NoiseFloorReading) : NoiseFloorPoll

    /** No radio to read from (`sessionProvider()` was nil on iOS). */
    data object Disconnected : NoiseFloorPoll

    /** The radio-stats request failed. */
    data object ReadFailed : NoiseFloorPoll
}

/**
 * Runs one poll of [fetch], classifying a null result as [NoiseFloorPoll.Disconnected] and a thrown
 * request as [NoiseFloorPoll.ReadFailed]. Cancellation still stops the poll, and an Error still leaves it.
 */
suspend fun pollNoiseFloor(fetch: suspend () -> NoiseFloorReading?): NoiseFloorPoll =
    runExceptionFallback({ NoiseFloorPoll.ReadFailed }) {
        fetch()?.let { NoiseFloorPoll.Sample(it) } ?: NoiseFloorPoll.Disconnected
    }

/** The reading series and the error banner after one poll. */
data class NoiseFloorPollState(val readings: List<NoiseFloorReading>, val errorMessage: String?)

/**
 * Folds one [poll] into the series and the banner: a sample appends and clears the banner, a failure
 * leaves the series alone and shows its message. Mirrors `appendReading` clearing `errorMessage` and the
 * two failure branches of the Swift `NoiseFloorViewModel`.
 */
fun applyNoiseFloorPoll(
    poll: NoiseFloorPoll,
    readings: List<NoiseFloorReading>,
    disconnectedMessage: String,
    readFailedMessage: String,
): NoiseFloorPollState = when (poll) {
    is NoiseFloorPoll.Sample -> NoiseFloorPollState(appendNoiseFloorReading(readings, poll.reading), null)
    NoiseFloorPoll.Disconnected -> NoiseFloorPollState(readings, disconnectedMessage)
    NoiseFloorPoll.ReadFailed -> NoiseFloorPollState(readings, readFailedMessage)
}
