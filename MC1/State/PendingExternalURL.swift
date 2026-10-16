import Foundation

/// App-level staging for a URL iOS delivers at or near cold launch, when routing
/// it synchronously would lose it. The before-first-unlock `AppState` swap drops
/// anything routed into the throwaway instance, and a failed auto-reconnect
/// during `AppState.initialize()` clears pending deep-link state before the
/// confirmation sheet can present. The URL is held here and routed only once
/// initialization has settled, so it survives both.
///
/// Mirrors `IntentBridge`: a reference type stored as a `let` on `MC1App` so it
/// outlives the `AppState` swap, mutated through `submit` / `markReady` rather
/// than a stored property a value-typed `App` cannot reassign from its `body`.
@MainActor
final class PendingExternalURL {
  private(set) var url: URL?
  private(set) var isReady = false

  /// Records a URL from iOS and routes it now if initialization has settled,
  /// otherwise holds it for `markReady`.
  func submit(_ url: URL, appState: AppState) {
    self.url = url
    drainIfReady(appState)
  }

  /// Records that initialization has settled and routes any URL held from
  /// launch. Called once at the tail of `MC1App`'s launch task, after
  /// `AppState.initialize()` and foreground reconciliation, so the failed
  /// reconnect teardown has already run and cannot wipe a freshly staged link.
  func markReady(_ appState: AppState) {
    isReady = true
    drainIfReady(appState)
  }

  /// Routes the held URL once ready, then drops it so a warm-app `submit` and
  /// the end-of-launch `markReady` cannot both route the same URL. A no-op
  /// until `markReady` or when nothing is held.
  private func drainIfReady(_ appState: AppState) {
    guard isReady, let url else { return }
    ChatLinkRouter.routeExternalOpen(url, appState: appState)
    self.url = nil
  }
}
