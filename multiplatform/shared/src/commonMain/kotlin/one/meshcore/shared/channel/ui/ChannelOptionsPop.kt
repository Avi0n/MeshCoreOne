package one.meshcore.shared.channel.ui

/**
 * Which Channel Options destination system Back should pop, matching iOS `ChannelOptionsSheet`
 * `navigationDestination` (create/join/scan, then the options hub, then the sheet itself).
 */
internal enum class ChannelOptionsPop {
    HUB,
    SHEET,
}

internal fun channelOptionsPopChild(hasSelectedOption: Boolean): ChannelOptionsPop =
    if (hasSelectedOption) ChannelOptionsPop.HUB else ChannelOptionsPop.SHEET
