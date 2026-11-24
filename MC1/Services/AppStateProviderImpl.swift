import MC1Services
import UIKit

/// UIKit-based implementation of AppStateProvider.
///
/// Checks UIApplication.shared.applicationState to determine if app is in foreground.
/// MainActor-isolated with async getter to allow cross-actor access.
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
}
