package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageStatus

/**
 * The status and metadata row beneath a bubble. Fully structural equality drives row diffing. Mirrors
 * Swift `MessageFooter`, including hop, path, and region metadata without reshaping [MessageItem].
 *
 * [status] stays the raw enum (never a pre-localized string) so a language or clock-format change resolves
 * to the right glyph and accessibility text at render time without rebuilding items. [isChannelMessage]
 * lets the renderer treat a channel `.sent` as terminal success while a DM `.sent` still reads as
 * "sending". [sendTimeToShowEpochSeconds] is the clock-corrected send time (null means do not show it);
 * [sendTimeWasCorrected] flags that the app substituted a corrected value for a skewed sender clock.
 *
 * The hop / path / region slots render as separate incoming chips in hop, path, region order.
 * [showHop] and [regionToShow] are already gated on flood routing (region is null when hidden);
 * [hopCount] carries the raw count so the hop chip can show zero. [formattedPath] is the
 * pre-formatted routing path (null when there is nothing to show).
 */
data class MessageFooter(
    val showStatusRow: Boolean,
    val status: MessageStatus,
    val isChannelMessage: Boolean,
    val heardRepeats: Int,
    val showHeardCount: Boolean = false,
    val retryAttempt: Int,
    val maxRetryAttempts: Int,
    val sendCount: Int,
    val sendTimeToShowEpochSeconds: Double?,
    val sendTimeWasCorrected: Boolean,
    val showHop: Boolean,
    val hopCount: Int,
    val formattedPath: String?,
    val regionToShow: String?,
    /** Candidate region names when the chip is a multi-match; empty when unique or hidden. */
    val regionMatchNames: List<String> = emptyList(),
) {
    val incomingHeardTotal: Int get() = 1 + heardRepeats
}
