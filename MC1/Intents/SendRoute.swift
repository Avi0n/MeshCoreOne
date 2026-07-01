import MC1Services

/// How a send should proceed for a given readiness, derived purely from
/// `DeviceConnectionState` so the decision is testable without a live radio.
/// This shadows nothing: it maps the existing connection rungs onto the one
/// branch the send cares about (durable queue vs foreground vs throw).
enum SendRoute: Equatable {
  /// `.ready`: the queue drains now, so enqueue headlessly.
  case headlessQueue
  /// `.syncing`: services exist and the row persists, but the queue drains
  /// only once sync clears, so the dialog says "after sync".
  case queueAfterSync
  /// `.connected` (services may be nil) or `.connecting`: do not enqueue
  /// through a possibly-nil service; foreground-escalate instead.
  case foregroundEscalate
  /// `.disconnected` with nothing to restore: retrying cannot help, so throw.
  /// A restorable disconnect instead resolves to `foregroundEscalate` via
  /// `disconnectedRoute(hasRestorableRadio:)`.
  case notConnected
}
