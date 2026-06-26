import Foundation

/// Tuning constants for ChatTableViewController scroll scheduling. A file-scope
/// enum (not nested in the generic controller) so static stored properties are
/// legal.
enum ChatScrollConstants {
    /// Maximum times a scroll-to-target is retried while the applied snapshot
    /// catches up to the items model before the target is abandoned.
    static let pendingScrollMaxRetries = 3

    /// Delay between scroll-to-target retries while waiting for the applied
    /// snapshot to drain.
    static let pendingScrollRetryDelay: Duration = .milliseconds(80)

    /// contentOffset.y at or below which the flipped table is treated as resting
    /// at the visual bottom. Small to absorb float imprecision only.
    static let bottomDetectionEpsilon: CGFloat = 1

    /// Looser bottom threshold used right after a programmatic scroll-to-bottom,
    /// whose animation may not land exactly at zero.
    static let bottomLandingEpsilon: CGFloat = 10

    /// Distance (in rows) from the oldest loaded message at which to trigger
    /// loading the next older page.
    static let nearTopTriggerDistance = 10

    /// Delay that lets a SwiftUI/table layout pass settle before a follow-up
    /// scroll or visibility check reads positions.
    static let layoutSettleDelay: Duration = .milliseconds(100)

    /// Delay before replaying a pending scroll-to-target queued during an items
    /// apply, giving the snapshot time to install the target row.
    static let pendingScrollInitialDelay: Duration = .milliseconds(120)

    /// Delay before an animated scroll-to-target begins, letting the target row's
    /// self-sizing layout settle so the landing offset is correct.
    static let scrollToTargetDelay: Duration = .milliseconds(180)
}
