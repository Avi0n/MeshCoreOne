import MC1Services
import UIKit

/// UIKit-based implementation of AppStateProvider.
///
/// Checks UIApplication.shared.applicationState to determine if app is in foreground.
/// MainActor-isolated with async getter to allow cross-actor access.
@MainActor
final class AppStateProviderImpl: AppStateProvider {
  init() {}

  nonisolated var isInForeground: Bool {
    get async {
      await MainActor.run {
        UIApplication.shared.applicationState != .background
      }
    }
  }
}
