import MC1Services
import UIKit

@MainActor
final class AppStateProviderImpl: AppStateProvider {
  private let isInForegroundProvider: @MainActor () -> Bool

  init(
    isInForeground: @escaping @MainActor () -> Bool = {
      UIApplication.shared.applicationState != .background
    }
  ) {
    isInForegroundProvider = isInForeground
  }

  nonisolated var isInForeground: Bool {
    get async {
      await MainActor.run {
        isInForegroundProvider()
      }
    }
  }

  nonisolated func runBackgroundTask(named name: String, operation: @Sendable () async -> Void) async {
    await BackgroundTaskRunner.live.run(named: name) {
      await operation()
    }
  }
}
