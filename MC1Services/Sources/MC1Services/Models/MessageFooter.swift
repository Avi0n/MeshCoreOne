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
    public let showStatusRow: Bool
    public let status: MessageStatus
    public let heardRepeats: Int
    public let retryAttempt: Int
    public let maxRetryAttempts: Int
    public let sendCount: Int

    public init(
        showHop: Bool,
        hopCount: Int,
        formattedPath: String?,
        regionToShow: String?,
        showStatusRow: Bool,
        status: MessageStatus,
        heardRepeats: Int,
        retryAttempt: Int,
        maxRetryAttempts: Int,
        sendCount: Int
    ) {
        self.showHop = showHop
        self.hopCount = hopCount
        self.formattedPath = formattedPath
        self.regionToShow = regionToShow
        self.showStatusRow = showStatusRow
        self.status = status
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
            showStatusRow: showStatusRow,
            status: status,
            heardRepeats: heardRepeats,
            retryAttempt: retryAttempt,
            maxRetryAttempts: maxRetryAttempts,
            sendCount: sendCount
        )
    }
}
