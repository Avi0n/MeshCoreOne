import Foundation

/// Errors thrown from the chat send queues' send closures. These surface to
/// `onDrain` so `sendErrorMessage` shows the user a localized "Unable to Send"
/// alert rather than silently dropping the envelope.
enum ChatSendQueueError: Error {

    /// The send queue drained an envelope while the services it needs were
    /// unbound — typically the hydration-before-BLE-rebind window after a
    /// crash recovery. The `PendingSend` row must survive so a later drain
    /// (once services are bound) retries the same envelope. `onError`
    /// switches on the error type to decide whether to delete the row.
    case transientUnavailable

    /// The send queue drained an envelope while a permanent error occurred —
    /// service contract violation, encoding failure, etc. `onError` deletes
    /// the `PendingSend` row because retrying cannot succeed.
    case servicesUnavailable
}

extension ChatSendQueueError {
    /// True when the row should be preserved for a future drain. False when
    /// `onError` must delete the row because retrying will not succeed.
    var isTransient: Bool {
        switch self {
        case .transientUnavailable: true
        case .servicesUnavailable: false
        }
    }
}
