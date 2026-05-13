import Foundation

/// Errors thrown from the chat send queues' send closures. These surface to
/// `onDrain` so `sendErrorMessage` shows the user a localized "Unable to Send"
/// alert rather than silently dropping the envelope.
enum ChatSendQueueError: Error {
    /// The send queue drained an envelope while the services it needs were
    /// unbound — typically a BLE disconnect that nilled `appState.services`
    /// before `configure*()` rebound them.
    case servicesUnavailable
}
