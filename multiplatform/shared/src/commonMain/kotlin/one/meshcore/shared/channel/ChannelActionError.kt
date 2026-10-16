package one.meshcore.shared.channel

/**
 * An error surfaced by a channel create/join/info flow, resolved to a localized message in the UI layer
 * (the Kotlin analogue of the iOS sheets' `errorMessage` strings). The typed cases map to fixed L10n keys;
 * [Service] carries a lower-layer failure whose own English text is only the developer fallback, and is
 * localized at the UI layer the way iOS resolves `error.userFacingMessage`.
 */
sealed interface ChannelActionError {
    /** No live radio to act against (`connectedDevice == nil`). */
    data object NoDeviceConnected : ChannelActionError

    /** Connected, but the per-connection services are not available yet. */
    data object ServicesUnavailable : ChannelActionError

    /** No free user slot to place the channel in. */
    data object NoSlots : ChannelActionError

    /** A hashtag name failed validation and cannot become a channel. */
    data object InvalidName : ChannelActionError

    /** The secret hex could not be decoded to bytes. */
    data object InvalidSecretFormat : ChannelActionError

    /** The write succeeded on the radio but the resulting channel row could not be read back. */
    data object LoadFailed : ChannelActionError

    data object PinSaveFailed : ChannelActionError

    /** Any other lower-layer failure, localized from the typed error at the UI layer. */
    data class Service(val error: ChannelServiceError) : ChannelActionError
}

/**
 * Which unavailable state a channel flow found, for the branch where the radio id or the channel
 * service is missing. A live [radioId] with no service means the radio is connected but its
 * per-connection services are not built yet, which iOS reports as its own error rather than as a
 * disconnected radio.
 */
internal fun channelUnavailableError(radioId: one.meshcore.shared.model.Uuid?): ChannelActionError =
    if (radioId == null) ChannelActionError.NoDeviceConnected else ChannelActionError.ServicesUnavailable
