import Foundation

/// Grouping signal — first-in-cluster, show timestamp, show divider.
public struct GroupingFlags: Sendable, Hashable {
    public let showTimestamp: Bool
    public let showDirectionGap: Bool
    public let showSenderName: Bool
    public let showNewMessagesDivider: Bool
    /// True for the first message of a new calendar day; drives the day separator.
    public let showDayDivider: Bool

    public init(
        showTimestamp: Bool,
        showDirectionGap: Bool,
        showSenderName: Bool,
        showNewMessagesDivider: Bool,
        showDayDivider: Bool = false
    ) {
        self.showTimestamp = showTimestamp
        self.showDirectionGap = showDirectionGap
        self.showSenderName = showSenderName
        self.showNewMessagesDivider = showNewMessagesDivider
        self.showDayDivider = showDayDivider
    }
}
