import CoreLocation
import Foundation
@testable import MC1
import Testing

@Suite("LocationService Tests")
@MainActor
struct LocationServiceTests {
  private final class MockLocationManager: LocationManagerControlling {
    weak var delegate: (any CLLocationManagerDelegate)?
    var desiredAccuracy: CLLocationAccuracy = 0
    private(set) var requestPermissionCount = 0
    private(set) var requestLocationCount = 0

    func requestWhenInUseAuthorization() {
      requestPermissionCount += 1
    }

    func requestLocation() {
      requestLocationCount += 1
    }
  }

  struct DeferredRequestCase: Sendable {
    let status: CLAuthorizationStatus
    let expectedLocations: Int
    let expectedPrompts: Int
  }

  struct PermissionCase: Sendable {
    let status: CLAuthorizationStatus
    let expectedPrompts: Int
  }

  @Test
  func `init does not create CLLocationManager and starts unknown`() {
    var createdCount = 0
    let service = LocationService {
      createdCount += 1
      return MockLocationManager()
    }

    #expect(createdCount == 0)
    #expect(!service.hasInitializedLocationManager)
    #expect(service.resolvedAuthorizationStatus == nil)
    #expect(service.authorizationStatus == .notDetermined)
    #expect(!service.isAuthorized)
    #expect(!service.hasRequestedPermission)
    #expect(!service.isLocationDenied)
  }

  @Test
  func `inert service never creates manager and rejects current location`() async {
    var createdCount = 0
    let service = LocationService(isInert: true) {
      createdCount += 1
      return MockLocationManager()
    }

    service.activateIfNeeded()
    service.requestPermissionIfNeeded()
    service.requestLocation()
    let status = await service.resolveAuthorizationStatus(timeout: .milliseconds(20))

    #expect(createdCount == 0)
    #expect(!service.hasInitializedLocationManager)
    #expect(status == .notDetermined)

    await expectLocationError {
      _ = try await service.requestCurrentLocation()
    } matches: { error in
      if case .notAuthorized(.notDetermined) = error { return true }
      return false
    }
  }

  @Test(arguments: [
    DeferredRequestCase(status: .authorizedWhenInUse, expectedLocations: 1, expectedPrompts: 0),
    DeferredRequestCase(status: .notDetermined, expectedLocations: 0, expectedPrompts: 1),
    DeferredRequestCase(status: .denied, expectedLocations: 0, expectedPrompts: 0),
  ])
  func `requestLocation defers until initial authorization callback arrives`(
    _ testCase: DeferredRequestCase
  ) {
    let mock = MockLocationManager()
    let service = LocationService { mock }

    service.requestLocation()
    #expect(service.hasInitializedLocationManager)
    #expect(mock.requestLocationCount == 0)
    #expect(mock.requestPermissionCount == 0)

    service.handleAuthorizationChange(testCase.status)

    #expect(mock.requestLocationCount == testCase.expectedLocations)
    #expect(mock.requestPermissionCount == testCase.expectedPrompts)
  }

  @Test(arguments: [
    PermissionCase(status: .authorizedWhenInUse, expectedPrompts: 0),
    PermissionCase(status: .denied, expectedPrompts: 0),
    PermissionCase(status: .notDetermined, expectedPrompts: 1),
  ])
  func `requestPermissionIfNeeded prompts only if status resolves to notDetermined`(
    _ testCase: PermissionCase
  ) {
    let mock = MockLocationManager()
    let service = LocationService { mock }

    service.requestPermissionIfNeeded()
    #expect(mock.requestPermissionCount == 0)

    service.handleAuthorizationChange(testCase.status)
    #expect(mock.requestPermissionCount == testCase.expectedPrompts)
  }

  @Test
  func `resolveAuthorizationStatus wakes concurrent waiters and returns immediately thereafter`() async {
    let mock = MockLocationManager()
    let service = LocationService { mock }

    async let first = service.resolveAuthorizationStatus(timeout: .seconds(1))
    async let second = service.resolveAuthorizationStatus(timeout: .seconds(1))
    await Task.yield()

    service.handleAuthorizationChange(.authorizedAlways)

    let statuses = await [first, second]
    #expect(statuses == [.authorizedAlways, .authorizedAlways])
    #expect(await service.resolveAuthorizationStatus(timeout: .milliseconds(10)) == .authorizedAlways)
  }

  @Test
  func `resolveAuthorizationStatus falls back to notDetermined on timeout`() async {
    let mock = MockLocationManager()
    let service = LocationService { mock }

    let status = await service.resolveAuthorizationStatus(timeout: .milliseconds(20))

    #expect(status == .notDetermined)
    #expect(service.resolvedAuthorizationStatus == nil)
    #expect(service.hasInitializedLocationManager)
  }

  @Test
  func `requestCurrentLocation awaits initial status before checking denial`() async {
    let mock = MockLocationManager()
    let service = LocationService(initialAuthorizationTimeout: .seconds(1)) { mock }

    let task = Task {
      try await service.requestCurrentLocation()
    }
    await Task.yield()
    service.handleAuthorizationChange(.denied)

    await expectLocationError {
      _ = try await task.value
    } matches: { error in
      if case .notAuthorized(.denied) = error { return true }
      return false
    }
  }

  @Test
  func `requestCurrentLocation throws permissionTimeout when initial authorization times out`() async {
    let mock = MockLocationManager()
    let service = LocationService(initialAuthorizationTimeout: .milliseconds(20)) { mock }

    await expectLocationError {
      _ = try await service.requestCurrentLocation()
    } matches: { error in
      if case .permissionTimeout = error { return true }
      return false
    }
  }

  private func expectLocationError(
    _ operation: () async throws -> Void,
    matches: (LocationServiceError) -> Bool
  ) async {
    do {
      try await operation()
      Issue.record("Expected LocationServiceError")
    } catch let error as LocationServiceError {
      #expect(matches(error), "Unexpected LocationServiceError: \(error)")
    } catch {
      Issue.record("Unexpected error: \(error)")
    }
  }
}
