import CoreLocation
import Foundation
import MC1Services
import UserNotifications

enum OnboardingStep: String, CaseIterable, Hashable, Codable {
    case welcome
    case permissions
    case pair
    case region
    case preset
}

/// Manages onboarding completion flag and navigation path.
@Observable
@MainActor
public final class OnboardingState {

    private let defaults: UserDefaults

    /// Whether onboarding is complete (persisted to UserDefaults)
    var hasCompletedOnboarding: Bool {
        didSet {
            defaults.set(hasCompletedOnboarding, forKey: AppStorageKey.hasCompletedOnboarding.rawValue)
        }
    }

    /// Navigation path for onboarding flow
    var onboardingPath: [OnboardingStep] = []

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.hasCompletedOnboarding = defaults.bool(forKey: AppStorageKey.hasCompletedOnboarding.rawValue)
    }

    /// Mark onboarding as complete
    func completeOnboarding() {
        hasCompletedOnboarding = true
    }

    /// Reset onboarding state
    func resetOnboarding() {
        hasCompletedOnboarding = false
        onboardingPath = []
    }
}

extension OnboardingState {
    /// Computes the `NavigationStack` starting path. The view at the top of the
    /// path is what the user lands on; `WelcomeView()` is the root, so any
    /// returned path is pushed *above* it.
    ///
    /// Reads notification authorization directly from `UNUserNotificationCenter`
    /// rather than `PermissionsCoordinator` (view-scoped, async-init).
    ///
    /// `regionAlreadySet` lets a returning user skip past the region step when
    /// `AppState.regionSelection` is already populated — without it, partially
    /// onboarded users land on `.region` even though they answered it last time.
    func suggestedStartingPath(
        connectionManager: ConnectionManager,
        locationAuthorizationStatus: CLAuthorizationStatus,
        regionAlreadySet: Bool
    ) async -> [OnboardingStep] {
        guard !hasCompletedOnboarding else { return [] }
        // `pairedAccessoriesCount` is the AccessorySetupKit count, always 0 on macOS (no
        // registry), so fall back to `lastConnectedDeviceID` — set only after a real successful
        // connect and cleared when a device is forgotten, never by a backup import — as the
        // platform-independent "this install has actually connected a radio" signal. A raw
        // saved-device count would wrongly resume for backup-restored shadows and demoted ghosts.
        guard connectionManager.pairedAccessoriesCount > 0
            || connectionManager.lastConnectedDeviceID != nil else { return [] }

        let notificationStatus = await UNUserNotificationCenter.current()
            .notificationSettings().authorizationStatus

        let permissionsHandled = locationAuthorizationStatus != .notDetermined
                              && notificationStatus != .notDetermined

        guard permissionsHandled else { return [.permissions] }

        if regionAlreadySet {
            return [.permissions, .pair, .preset]
        }
        return [.permissions, .pair, .region]
    }
}
