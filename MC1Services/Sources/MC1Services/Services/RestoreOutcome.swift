import Foundation

/// Non-error result of an `AppStore.sync()`-driven restore attempt. User dismissal of the
/// iCloud password prompt or the AppStore-sync confirmation sheet is an outcome, not an error
/// (the caller must avoid celebrating a cancellation as a successful restore).
public enum RestoreOutcome: Sendable, Equatable {
    case completed
    case cancelled
}
