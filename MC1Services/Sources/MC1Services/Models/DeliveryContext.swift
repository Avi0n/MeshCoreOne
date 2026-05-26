import Foundation

/// How an incoming message reached the app, used to decide its persisted sort order.
///
/// This value is ephemeral — it is never stored on `Message` or `MessageDTO`. It only
/// flows through the message-handler pipeline so the sort date can be derived per the
/// delivery path:
/// - `live` keeps just-arrived messages at the bottom of the transcript (sort by receive time).
/// - `initialSync` positions a drained backlog batch as one contiguous block at the drain
///   time carried in `anchor`, so reconnect history lands together near the bottom rather
///   than scattered through scrollback by send time.
public enum DeliveryContext: Sendable {
    /// Drained from the device's stored backlog during initial connect or resync.
    /// `anchor` is captured once per drain so every message in the batch shares a sort
    /// date and forms a contiguous block positioned at delivery time, send-ordered within.
    case initialSync(anchor: Date)
    /// Pushed in real time while connected.
    case live
}
