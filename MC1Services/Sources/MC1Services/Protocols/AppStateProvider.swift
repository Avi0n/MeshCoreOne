import Foundation

/// Bridges app foreground state and background task assertions into `MC1Services` without importing UIKit.
public protocol AppStateProvider: Sendable {
  var isInForeground: Bool { get async }

  /// Holds an OS background task assertion around `operation` when supported by the platform.
  func runBackgroundTask(named name: String, operation: @Sendable () async -> Void) async
}

public extension AppStateProvider {
  func runBackgroundTask(named _: String, operation: @Sendable () async -> Void) async {
    await operation()
  }
}
