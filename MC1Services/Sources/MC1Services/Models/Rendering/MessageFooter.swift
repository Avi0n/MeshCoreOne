import Foundation

/// Hop / path / region footer slots — derived by `MessageFragmentBuilder`
/// from the per-radio show-flag inputs plus `formattedPath`. Stays a struct
/// (not a fragment) because hop / path / region render as one footer row,
/// not three independent rows.
///
/// `status` is stored as `MessageStatus` (the raw enum) rather than a pre-built
/// localized status string. Resolving `L10n` at build time would freeze the
/// localized text into the fragment, so a language switch mid-session would
/// leave bubbles displaying stale strings until a rebuild. The view body
/// resolves the localized text at render time.
public struct MessageFooter: Sendable, Hashable {
    public let showHop: Bool
    public let hopCount: Int
    public let formattedPath: String?
    public let regionToShow: String?
    /// Send time to display inside the bubble; nil means do not show it. Holds the
    /// clock-corrected `senderDate`, so a skewed sender clock never surfaces a
    /// misleading time here — `sendTimeWasCorrected` flags the substitution and the
    /// raw wire value stays available in the message info sheet. Stored as a `Date`,
    /// not a formatted string, so a mid-session 12h/24h or language change reformats
    /// at render time without rebaking items — the same rule that keeps `status` a
    /// raw enum here.
    public let sendTimeToShow: Date?
    /// Whether the app substituted a corrected `timestamp` because the sender's
    /// wire clock was invalid. Only meaningful when `sendTimeToShow != nil`;
    /// drives the warning badge next to the time.
    public let sendTimeWasCorrected: Bool
    public let showStatusRow: Bool
    public let status: MessageStatus
    /// Distinguishes a channel broadcast from a DM at render time. `BubbleStatusRow`
    /// uses it so a DM's transient `.sent` reads as "Sending..." while a channel's
    /// `.sent` stays terminal success. Render-only, never persisted.
    public let isChannelMessage: Bool
    public let heardRepeats: Int
    public let retryAttempt: Int
    public let maxRetryAttempts: Int
    public let sendCount: Int

    public init(
        showHop: Bool,
        hopCount: Int,
        formattedPath: String?,
        regionToShow: String?,
        sendTimeToShow: Date?,
        sendTimeWasCorrected: Bool,
        showStatusRow: Bool,
        status: MessageStatus,
        isChannelMessage: Bool,
        heardRepeats: Int,
        retryAttempt: Int,
        maxRetryAttempts: Int,
        sendCount: Int
    ) {
        self.showHop = showHop
        self.hopCount = hopCount
        self.formattedPath = formattedPath
        self.regionToShow = regionToShow
        self.sendTimeToShow = sendTimeToShow
        self.sendTimeWasCorrected = sendTimeWasCorrected
        self.showStatusRow = showStatusRow
        self.status = status
        self.isChannelMessage = isChannelMessage
        self.heardRepeats = heardRepeats
        self.retryAttempt = retryAttempt
        self.maxRetryAttempts = maxRetryAttempts
        self.sendCount = sendCount
    }

    /// Returns a new footer with `status` overridden. Eliminates the 10-field
    /// rebuild at status-flip sites.
    public func with(status: MessageStatus) -> MessageFooter {
        MessageFooter(
            showHop: showHop,
            hopCount: hopCount,
            formattedPath: formattedPath,
            regionToShow: regionToShow,
            sendTimeToShow: sendTimeToShow,
            sendTimeWasCorrected: sendTimeWasCorrected,
            showStatusRow: showStatusRow,
            status: status,
            isChannelMessage: isChannelMessage,
            heardRepeats: heardRepeats,
            retryAttempt: retryAttempt,
            maxRetryAttempts: maxRetryAttempts,
            sendCount: sendCount
        )
    }
}
