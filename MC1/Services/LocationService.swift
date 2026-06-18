import CoreLocation
import OSLog

enum LocationServiceError: Error, LocalizedError, Sendable {
    case notAuthorized(CLAuthorizationStatus)
    case requestInProgress
    case permissionTimeout
    case locationTimeout
    case requestFailed(String)

    var errorDescription: String? {
        switch self {
        case .notAuthorized(let status):
            switch status {
            case .notDetermined:
                "Location permission is required to update your node's location."
            case .denied, .restricted:
                "Location permission is denied. Enable it in Settings to update your node's location."
            default:
                "Location permission is not available."
            }
        case .requestInProgress:
            "A location request is already in progress."
        case .permissionTimeout:
            "Timed out while waiting for location permission."
        case .locationTimeout:
            "Timed out while requesting location."
        case .requestFailed(let message):
            "Location request failed: \(message)"
        }
    }
}

/// App-wide location service for managing location permissions and access.
/// Used by MapView, LineOfSightView, ContactsListView, and other location-dependent features.
@Observable
@MainActor
final class LocationService: NSObject, CLLocationManagerDelegate {

    // MARK: - Properties

    private let logger = Logger(subsystem: "com.mc1", category: "LocationService")
    private let locationManager: CLLocationManager

    private var requestContinuation: CheckedContinuation<CLLocation, Error>?
    private var locationTimeoutTask: Task<Void, Never>?

    private var authorizationContinuation: CheckedContinuation<CLAuthorizationStatus, Error>?
    private var permissionTimeoutTask: Task<Void, Never>?

    /// Current authorization status
    private(set) var authorizationStatus: CLAuthorizationStatus

    /// Current device location (nil if unavailable or not yet determined)
    private(set) var currentLocation: CLLocation?

    /// Whether a location request is in progress
    private(set) var isRequestingLocation = false

    /// Whether location services are authorized for use
    var isAuthorized: Bool {
        authorizationStatus == .authorizedWhenInUse || authorizationStatus == .authorizedAlways
    }

    /// Whether permission has been determined (not .notDetermined)
    var hasRequestedPermission: Bool {
        authorizationStatus != .notDetermined
    }

    /// Whether location is denied or restricted
    var isLocationDenied: Bool {
        authorizationStatus == .denied || authorizationStatus == .restricted
    }

    // MARK: - Initialization

    override init() {
        locationManager = CLLocationManager()
        authorizationStatus = locationManager.authorizationStatus
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    // MARK: - Public Methods

    /// Request location permission if not already determined.
    /// Call this when a location-dependent feature is accessed.
    func requestPermissionIfNeeded() {
        guard authorizationStatus == .notDetermined else {
            let raw = self.authorizationStatus.rawValue
            logger.debug("Location permission already determined: \(String(describing: raw))")
            return
        }

        logger.info("Requesting location permission")
        locationManager.requestWhenInUseAuthorization()
    }

    /// Request a one-shot location update.
    /// Call this when you need the current location (e.g., for distance sorting).
    func requestLocation() {
        guard isAuthorized else {
            logger.debug("Cannot request location: not authorized")
            requestPermissionIfNeeded()
            return
        }

        guard !isRequestingLocation else {
            logger.debug("Location request already in progress")
            return
        }

        logger.info("Requesting one-shot location update")
        isRequestingLocation = true
        locationManager.requestLocation()
    }

    /// Request current location asynchronously with timeout.
    /// Handles permission prompting if needed.
    func requestCurrentLocation(timeout: Duration = .seconds(10)) async throws -> CLLocation {
        guard requestContinuation == nil, authorizationContinuation == nil else {
            throw LocationServiceError.requestInProgress
        }

        if !isAuthorized {
            if authorizationStatus == .notDetermined {
                requestPermissionIfNeeded()
                _ = try await waitForAuthorizationDecision(timeout: .seconds(30))
            }

            guard isAuthorized else {
                throw LocationServiceError.notAuthorized(authorizationStatus)
            }
        }

        isRequestingLocation = true

        return try await withCheckedThrowingContinuation { continuation in
            requestContinuation = continuation
            locationManager.requestLocation()

            locationTimeoutTask?.cancel()
            locationTimeoutTask = Task { @MainActor [weak self] in
                do {
                    try await Task.sleep(for: timeout)
                } catch {
                    return
                }
                guard let self, let continuation = self.requestContinuation else { return }

                self.requestContinuation = nil
                self.isRequestingLocation = false
                continuation.resume(throwing: LocationServiceError.locationTimeout)
            }
        }
    }

    // MARK: - Private Methods

    private func waitForAuthorizationDecision(timeout: Duration) async throws -> CLAuthorizationStatus {
        guard authorizationStatus == .notDetermined else { return authorizationStatus }
        guard authorizationContinuation == nil else {
            throw LocationServiceError.requestInProgress
        }

        return try await withCheckedThrowingContinuation { continuation in
            authorizationContinuation = continuation

            permissionTimeoutTask?.cancel()
            permissionTimeoutTask = Task { @MainActor [weak self] in
                do {
                    try await Task.sleep(for: timeout)
                } catch {
                    return
                }
                guard let self, let continuation = self.authorizationContinuation else { return }

                self.authorizationContinuation = nil
                continuation.resume(throwing: LocationServiceError.permissionTimeout)
            }
        }
    }

    // MARK: - CLLocationManagerDelegate

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorizationStatus = status
            self.logger.info("Location authorization changed: \(String(describing: status.rawValue))")

            if status != .notDetermined, let authorizationContinuation = self.authorizationContinuation {
                self.authorizationContinuation = nil
                self.permissionTimeoutTask?.cancel()
                self.permissionTimeoutTask = nil
                authorizationContinuation.resume(returning: status)
            }

            if status == .denied || status == .restricted {
                if let continuation = self.requestContinuation {
                    self.requestContinuation = nil
                    self.locationTimeoutTask?.cancel()
                    self.locationTimeoutTask = nil
                    self.isRequestingLocation = false
                    continuation.resume(throwing: LocationServiceError.notAuthorized(status))
                }
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        Task { @MainActor in
            self.currentLocation = location
            self.isRequestingLocation = false
            self.logger.info("Location updated: \(location.coordinate.latitude), \(location.coordinate.longitude)")

            self.locationTimeoutTask?.cancel()
            self.locationTimeoutTask = nil

            if let continuation = self.requestContinuation {
                self.requestContinuation = nil
                continuation.resume(returning: location)
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            self.isRequestingLocation = false
            self.logger.error("Location request failed: \(error.localizedDescription)")

            self.locationTimeoutTask?.cancel()
            self.locationTimeoutTask = nil

            if let continuation = self.requestContinuation {
                self.requestContinuation = nil
                continuation.resume(throwing: LocationServiceError.requestFailed(error.localizedDescription))
            }
        }
    }
}
