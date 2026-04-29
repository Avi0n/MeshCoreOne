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
            defaults.set(hasCompletedOnboarding, forKey: "hasCompletedOnboarding")
        }
    }

    /// Navigation path for onboarding flow
    var onboardingPath: [OnboardingStep] = []

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.hasCompletedOnboarding = defaults.bool(forKey: "hasCompletedOnboarding")
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
    func suggestedStartingPath(
        connectionManager: ConnectionManager,
        locationAuthorizationStatus: CLAuthorizationStatus
    ) async -> [OnboardingStep] {
        guard !hasCompletedOnboarding else { return [] }
        guard connectionManager.pairedAccessoriesCount > 0 else { return [] }

        let notificationStatus = await UNUserNotificationCenter.current()
            .notificationSettings().authorizationStatus

        let permissionsHandled = locationAuthorizationStatus != .notDetermined
                              && notificationStatus != .notDetermined

        if permissionsHandled {
            return [.permissions, .pair, .region]
        } else {
            return [.permissions]
        }
    }
}
