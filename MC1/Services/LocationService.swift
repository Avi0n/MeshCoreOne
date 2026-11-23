import CoreLocation
import OSLog

enum LocationServiceError: Error, LocalizedError {
  case notAuthorized(CLAuthorizationStatus)
  case requestInProgress
  case permissionTimeout
  case locationTimeout
  case requestFailed(String)

  var errorDescription: String? {
    switch self {
    case let .notAuthorized(status):
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
    case let .requestFailed(message):
      "Location request failed: \(message)"
    }
  }
}

/// App-wide location service for managing location permissions and access.
/// Used by MapView, LineOfSightView, ContactsListView, and other location-dependent features.
@Observable
@MainActor
final class LocationService: NSObject, CLLocationManagerDelegate {
  // MARK: - Constants

  private static let initialAuthorizationTimeout: Duration = .seconds(5)
  private static let defaultLocationTimeout: Duration = .seconds(10)
  private static let permissionDecisionTimeout: Duration = .seconds(30)

  // MARK: - Properties

  private let logger = Logger(subsystem: "com.mc1", category: "LocationService")
  private let isInert: Bool
  private let initialAuthorizationTimeout: Duration
  private let managerFactory: @MainActor () -> any LocationManagerControlling
  private var locationManager: (any LocationManagerControlling)?

  private var requestContinuation: CheckedContinuation<CLLocation, Error>?
  private var locationTimeoutTask: Task<Void, Never>?

  private var authorizationContinuation: CheckedContinuation<CLAuthorizationStatus, Error>?
  private var permissionTimeoutTask: Task<Void, Never>?

  private var pendingPermissionRequest = false
  private var pendingLocationRequest = false
  private var initialAuthorizationWaiters: [UUID: (
    continuation: CheckedContinuation<CLAuthorizationStatus, Never>,
    timeoutTask: Task<Void, Never>
  )] = [:]

  /// Nil until CoreLocation delivers its first delegate callback.
  private(set) var resolvedAuthorizationStatus: CLAuthorizationStatus?

  /// Current authorization status (`.notDetermined` until CoreLocation reports otherwise).
  var authorizationStatus: CLAuthorizationStatus {
    resolvedAuthorizationStatus ?? .notDetermined
  }

  var hasInitializedLocationManager: Bool {
    locationManager != nil
  }

  /// Current device location (nil if unavailable or not yet determined)
  private(set) var currentLocation: CLLocation?

  /// Whether a location request is in progress
  private(set) var isRequestingLocation = false

  /// Whether location services are authorized for use
  var isAuthorized: Bool {
    resolvedAuthorizationStatus == .authorizedWhenInUse || resolvedAuthorizationStatus == .authorizedAlways
  }

  /// False while status is unknown or `.notDetermined`.
  var hasRequestedPermission: Bool {
    guard let status = resolvedAuthorizationStatus else { return false }
    return status != .notDetermined
  }

  /// Whether location is denied or restricted
  var isLocationDenied: Bool {
    resolvedAuthorizationStatus == .denied || resolvedAuthorizationStatus == .restricted
  }

  // MARK: - Initialization

  init(
    isInert: Bool = false,
    initialAuthorizationTimeout: Duration = LocationService.initialAuthorizationTimeout,
    managerFactory: @escaping @MainActor () -> any LocationManagerControlling = { CLLocationManager() }
  ) {
    self.isInert = isInert
    self.initialAuthorizationTimeout = initialAuthorizationTimeout
    self.managerFactory = managerFactory
    super.init()
  }

  // MARK: - Public Methods

  /// Wires `CLLocationManager` lazily; skips reading `authorizationStatus` to avoid synchronous `locationd` XPC.
  func activateIfNeeded() {
    guard !isInert, locationManager == nil else { return }
    let manager = managerFactory()
    manager.delegate = self
    manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    locationManager = manager
  }

  /// Awaits the first authorization callback when status is unknown; falls back to `.notDetermined` on timeout.
  func resolveAuthorizationStatus(timeout: Duration? = nil) async -> CLAuthorizationStatus {
    if let status = resolvedAuthorizationStatus {
      return status
    }
    guard !isInert else { return .notDetermined }
    activateIfNeeded()

    let waitDuration = timeout ?? initialAuthorizationTimeout
    let waiterID = UUID()
    return await withTaskCancellationHandler {
      await withCheckedContinuation { continuation in
        if let status = resolvedAuthorizationStatus {
          continuation.resume(returning: status)
          return
        }
        let timeoutTask = Task { @MainActor [weak self] in
          do {
            try await Task.sleep(for: waitDuration)
          } catch {
            return
          }
          guard let self, let waiter = self.initialAuthorizationWaiters.removeValue(forKey: waiterID) else {
            return
          }
          waiter.continuation.resume(returning: .notDetermined)
        }
        initialAuthorizationWaiters[waiterID] = (continuation, timeoutTask)
      }
    } onCancel: {
      Task { @MainActor [weak self] in
        guard let self, let waiter = self.initialAuthorizationWaiters.removeValue(forKey: waiterID) else {
          return
        }
        waiter.timeoutTask.cancel()
        waiter.continuation.resume(returning: self.resolvedAuthorizationStatus ?? .notDetermined)
      }
    }
  }

  /// Request location permission if not already determined.
  /// Call this when a location-dependent feature is accessed.
  func requestPermissionIfNeeded() {
    guard !isInert else { return }
    guard resolvedAuthorizationStatus != nil else {
      pendingPermissionRequest = true
      activateIfNeeded()
      return
    }
    guard authorizationStatus == .notDetermined else {
      let raw = authorizationStatus.rawValue
      logger.debug("Location permission already determined: \(String(describing: raw))")
      return
    }

    activateIfNeeded()
    logger.info("Requesting location permission")
    locationManager?.requestWhenInUseAuthorization()
  }

  /// Request a one-shot location update.
  /// Call this when you need the current location (e.g., for distance sorting).
  func requestLocation() {
    guard !isInert else { return }
    guard resolvedAuthorizationStatus != nil else {
      pendingLocationRequest = true
      activateIfNeeded()
      return
    }
    guard isAuthorized else {
      logger.debug("Cannot request location: not authorized")
      requestPermissionIfNeeded()
      return
    }

    guard !isRequestingLocation else {
      logger.debug("Location request already in progress")
      return
    }

    activateIfNeeded()
    logger.info("Requesting one-shot location update")
    isRequestingLocation = true
    locationManager?.requestLocation()
  }

  /// Request current location asynchronously with timeout.
  /// Handles permission prompting if needed.
  func requestCurrentLocation(timeout: Duration = LocationService.defaultLocationTimeout) async throws -> CLLocation {
    guard requestContinuation == nil, authorizationContinuation == nil else {
      throw LocationServiceError.requestInProgress
    }
    guard !isInert else {
      throw LocationServiceError.notAuthorized(.notDetermined)
    }

    if resolvedAuthorizationStatus == nil {
      _ = await resolveAuthorizationStatus()
      guard requestContinuation == nil, authorizationContinuation == nil else {
        throw LocationServiceError.requestInProgress
      }
      if resolvedAuthorizationStatus == nil {
        throw LocationServiceError.permissionTimeout
      }
    }

    if !isAuthorized {
      if authorizationStatus == .notDetermined {
        requestPermissionIfNeeded()
        _ = try await waitForAuthorizationDecision(timeout: Self.permissionDecisionTimeout)
      }

      guard isAuthorized else {
        throw LocationServiceError.notAuthorized(authorizationStatus)
      }
    }

    activateIfNeeded()
    isRequestingLocation = true

    return try await withCheckedThrowingContinuation { continuation in
      requestContinuation = continuation
      locationManager?.requestLocation()

      locationTimeoutTask?.cancel()
      locationTimeoutTask = Task { @MainActor [weak self] in
        do {
          try await Task.sleep(for: timeout)
        } catch {
          return
        }
        guard let self, let continuation = requestContinuation else { return }

        requestContinuation = nil
        isRequestingLocation = false
        continuation.resume(throwing: LocationServiceError.locationTimeout)
      }
    }
  }

  // MARK: - Private Methods

  func handleAuthorizationChange(_ status: CLAuthorizationStatus) {
    resolvedAuthorizationStatus = status
    logger.info("Location authorization changed: \(String(describing: status.rawValue))")

    let waiters = initialAuthorizationWaiters
    initialAuthorizationWaiters.removeAll()
    for waiter in waiters.values {
      waiter.timeoutTask.cancel()
      waiter.continuation.resume(returning: status)
    }

    if status != .notDetermined, let authorizationContinuation {
      self.authorizationContinuation = nil
      permissionTimeoutTask?.cancel()
      permissionTimeoutTask = nil
      authorizationContinuation.resume(returning: status)
    }

    if status == .denied || status == .restricted, let continuation = requestContinuation {
      requestContinuation = nil
      locationTimeoutTask?.cancel()
      locationTimeoutTask = nil
      isRequestingLocation = false
      continuation.resume(throwing: LocationServiceError.notAuthorized(status))
    }

    let shouldRequestLocation = pendingLocationRequest
    let shouldRequestPermission = pendingPermissionRequest
    pendingLocationRequest = false
    pendingPermissionRequest = false

    if shouldRequestLocation {
      if isAuthorized {
        requestLocation()
      } else if status == .notDetermined {
        requestPermissionIfNeeded()
      }
    } else if shouldRequestPermission, status == .notDetermined {
      requestPermissionIfNeeded()
    }
  }

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
        guard let self, let continuation = authorizationContinuation else { return }

        authorizationContinuation = nil
        continuation.resume(throwing: LocationServiceError.permissionTimeout)
      }
    }
  }

  // MARK: - CLLocationManagerDelegate

  nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    let status = manager.authorizationStatus
    Task { @MainActor in
      self.handleAuthorizationChange(status)
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
