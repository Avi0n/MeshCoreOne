import Foundation

/// One-to-many notifications broadcast by `ContactService` to its
/// `events()` subscribers.
public enum ContactServiceEvent: Sendable {
    /// Progress during a contact sync: `received` of `total` contacts persisted.
    case syncProgress(received: Int, total: Int)

    /// A contact was removed from the device, so node storage is no longer full.
    case nodeDeleted
}
