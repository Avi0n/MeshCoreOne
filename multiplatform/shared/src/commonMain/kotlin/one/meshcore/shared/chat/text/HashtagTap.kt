package one.meshcore.shared.chat.text

import one.meshcore.shared.channel.ChannelSecret
import one.meshcore.shared.runExceptionFallback

/** A channel the hashtag resolver matches a tap against: its stable id and its `#name`. */
data class HashtagChannelRef(val id: String, val name: String)

/** What a hashtag tap resolves to: open an already-joined channel, stage the join flow, or reject. */
sealed interface HashtagTapResult {
    /** A channel whose name matches the hashtag already exists; open it. */
    data class OpenChannel(val channelId: String) : HashtagTapResult

    /**
     * No matching channel (or no radio yet): stage the join sheet for [fullName] (the `#name` form). The
     * join UI derives its secret through [HashtagJoinSeam.deriveSecret].
     */
    data class StageJoin(val fullName: String) : HashtagTapResult

    /** The tapped name failed hashtag validation and cannot become a channel. */
    data object Invalid : HashtagTapResult
}

/**
 * Pure resolution for a hashtag tap, ported from the iOS `handleHashtagTap`. Normalizes and validates the
 * name into `#name`; with no radio or no matching channel it stages the join flow, otherwise it opens the
 * existing channel (matched case-insensitively by name).
 */
object HashtagTapResolver {
    fun resolve(rawName: String, hasRadio: Boolean, existingChannels: List<HashtagChannelRef>): HashtagTapResult {
        val fullName = HashtagDeeplink.fullChannelName(rawName) ?: return HashtagTapResult.Invalid
        if (!hasRadio) return HashtagTapResult.StageJoin(fullName)
        val existing = existingChannels.firstOrNull { it.name.equals(fullName, ignoreCase = true) }
        return if (existing != null) HashtagTapResult.OpenChannel(existing.id) else HashtagTapResult.StageJoin(fullName)
    }
}

/**
 * Resolves a hashtag tapped inside a message, fetching the radio's configured channels only when there is a
 * radio and the name is usable. A failed fetch falls back to staging the join flow, matching the iOS
 * `handleHashtagTap` catch — the user still gets an action rather than a silently swallowed tap.
 */
suspend fun resolveHashtagTap(
    rawName: String,
    hasRadio: Boolean,
    fetchChannels: suspend () -> List<HashtagChannelRef>,
): HashtagTapResult {
    if (HashtagDeeplink.fullChannelName(rawName) == null) return HashtagTapResult.Invalid
    val channels = if (hasRadio) configuredChannels(fetchChannels) else emptyList()
    return HashtagTapResolver.resolve(rawName, hasRadio, channels)
}

/** An unreadable channel store leaves the tap with no matches, so the caller stages the join instead. */
private suspend fun configuredChannels(fetch: suspend () -> List<HashtagChannelRef>): List<HashtagChannelRef> =
    runExceptionFallback({ emptyList() }, fetch)

/**
 * The join-hashtag secret seam. A hashtag channel's secret is `sha256("#name")[0:16]` (MeshCore spec), so
 * both the channel name and its passphrase carry the `#`. Delegates to
 * [ChannelSecret][one.meshcore.shared.channel.ChannelSecret]; the join UI calls this to configure the
 * slot once the user confirms.
 */
object HashtagJoinSeam {
    /** The 16-byte channel secret for a `#name` full channel name. */
    fun deriveSecret(fullChannelName: String): ByteArray =
        ChannelSecret.hashtagChannelSecret(fullChannelName.removePrefix("#"))
}
