import UIKit

/// Holds a background task open while `run` executes so the system does not suspend the
/// process mid-write. A SQLite lock held at suspension terminates the app with `0xdead10cc`.
@MainActor
struct BackgroundTaskRunner {
  typealias Begin = @MainActor (_ name: String, _ onExpiration: @escaping @MainActor () -> Void) -> UIBackgroundTaskIdentifier
  typealias End = @MainActor (UIBackgroundTaskIdentifier) -> Void

  let begin: Begin
  let end: End

  static let live = BackgroundTaskRunner(
    begin: { name, onExpiration in
      UIApplication.shared.beginBackgroundTask(withName: name, expirationHandler: onExpiration)
    },
    end: { UIApplication.shared.endBackgroundTask($0) }
  )

  private final class Assertion {
    var identifier = UIBackgroundTaskIdentifier.invalid
  }

  func run(named name: String, operation: () async -> Void) async {
    let assertion = Assertion()
    let end = end
    let release: @MainActor () -> Void = {
      guard assertion.identifier != .invalid else { return }
      end(assertion.identifier)
      assertion.identifier = .invalid
    }
    assertion.identifier = begin(name, release)
    await operation()
    release()
  }
}
