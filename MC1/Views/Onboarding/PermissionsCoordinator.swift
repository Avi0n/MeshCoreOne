@preconcurrency import CoreLocation
import UserNotifications

/// Coordinator for managing Location and Notification permission requests and state observation.
/// Uses delegate callbacks to update permission state immediately when user responds.
@Observable
@MainActor
final class PermissionsCoordinator: NSObject, CLLocationManagerDelegate {
    var locationAuthorization: CLAuthorizationStatus = .notDetermined
    var notificationAuthorization: UNAuthorizationStatus = .notDetermined

    private var locationManager: CLLocationManager?

    override init() {
        super.init()
        // Create location manager early to check current authorization
        locationManager = CLLocationManager()
        locationManager?.delegate = self
        locationAuthorization = locationManager?.authorizationStatus ?? .notDetermined

        // Check notification authorization
        Task {
            await checkNotificationAuthorization()
        }
    }

    func requestLocation() {
        locationManager?.requestWhenInUseAuthorization()
    }

    func requestNotifications() {
        Task {
            do {
                let granted = try await UNUserNotificationCenter.current().requestAuthorization(
                    options: [.alert, .sound, .badge]
                )
                notificationAuthorization = granted ? .authorized : .denied
            } catch {
                notificationAuthorization = .denied
            }
        }
    }

    func checkPermissions() {
        if let lm = locationManager {
            locationAuthorization = lm.authorizationStatus
        }
        Task {
            await checkNotificationAuthorization()
        }
    }

    private func checkNotificationAuthorization() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        notificationAuthorization = settings.authorizationStatus
    }

    // MARK: - CLLocationManagerDelegate

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.locationAuthorization = status
        }
    }
}
