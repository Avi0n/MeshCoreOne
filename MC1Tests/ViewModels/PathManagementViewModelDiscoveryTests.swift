import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("PathManagementViewModel Discovery")
struct PathManagementViewModelDiscoveryTests {
  @Test
  @MainActor
  func `Response with hops presents a success result`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())

    vm.handleDiscoveryResponse(hopCount: 2)

    #expect(vm.discoveryResult == .success(hopCount: 2))
    #expect(vm.showDiscoveryResult == true)
  }

  @Test
  @MainActor
  func `Response without a decodable path presents no-path-found`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())

    vm.handleDiscoveryResponse(hopCount: nil)

    #expect(vm.discoveryResult == .noPathFound)
    #expect(vm.showDiscoveryResult == true)
  }

  @Test
  @MainActor
  func `Response signals contact refresh`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    var refreshed = false
    vm.onContactNeedsRefresh = { refreshed = true }

    vm.handleDiscoveryResponse(hopCount: 1)

    #expect(refreshed)
  }

  @Test
  @MainActor
  func `Late response re-presents an already-shown timeout alert with the fresh result`() async throws {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    // Timeout already fired: the failure alert is on screen.
    vm.discoveryResult = .noPathFound
    vm.showDiscoveryResult = true

    vm.handleDiscoveryResponse(hopCount: 3)

    // A presented alert captures its message; it must dismiss first...
    #expect(vm.showDiscoveryResult == false)
    #expect(vm.discoveryResult == .success(hopCount: 3))

    // ...then re-present carrying the fresh result.
    try await waitUntil { vm.showDiscoveryResult }
    #expect(vm.discoveryResult == .success(hopCount: 3))
  }

  @Test
  @MainActor
  func `Cancelling discovery suppresses a pending alert re-present`() async throws {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    vm.discoveryResult = .noPathFound
    vm.showDiscoveryResult = true

    vm.handleDiscoveryResponse(hopCount: 3)
    vm.cancelDiscovery()

    try await Task.sleep(for: .seconds(1.5))
    #expect(vm.showDiscoveryResult == false)
  }

  // MARK: - Test helpers

  @MainActor
  private func waitUntil(
    timeout: Duration = .seconds(3),
    _ condition: () -> Bool
  ) async throws {
    let deadline = ContinuousClock.now + timeout
    while !condition() {
      if ContinuousClock.now > deadline {
        Issue.record("Condition not met within \(timeout)")
        return
      }
      try await Task.sleep(for: .milliseconds(50))
    }
  }

  private func makeSuiteDefaults() -> UserDefaults {
    UserDefaults(suiteName: "test.\(UUID().uuidString)")!
  }
}
