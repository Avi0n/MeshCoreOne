import Foundation

/// Shared bounds for radio-backed commands issued from list actions.
enum RadioCommandTimeout {
    /// Upper bound for a delete that round-trips to the radio (channel clear, room leave, remove
    /// node). Set above the firmware ack window on a congested mesh: too short re-admits a slow-
    /// but-successful delete as a spurious error. The confirmation-gated paths use it, so an expiry
    /// surfaces an error on a still-visible row, never a hidden one.
    static let delete: Duration = .seconds(7)
}
