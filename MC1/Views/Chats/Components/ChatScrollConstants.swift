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
}
