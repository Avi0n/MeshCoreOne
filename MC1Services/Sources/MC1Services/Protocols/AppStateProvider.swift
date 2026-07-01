import Foundation

/// Protocol for checking application foreground/background state.
///
/// Injected into services to avoid UIKit dependency in the service layer.
/// Property is async to support cross-actor access from non-MainActor contexts.
public protocol AppStateProvider: Sendable {
  /// Returns true if the app is currently in the foreground (active state).
  /// Async to allow MainActor-isolated implementations to be called from other actors.
  var isInForeground: Bool { get async }
}
