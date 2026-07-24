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
    vm.isDiscovering = true

    vm.handleDiscoveryResponse(hopCount: 2)

    #expect(vm.discoveryResult == .success(hopCount: 2))
    #expect(vm.showDiscoveryResult == true)
    #expect(vm.isDiscovering == false)
  }

  @Test
  @MainActor
  func `Response without a decodable path presents no-path-found`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    vm.isDiscovering = true

    vm.handleDiscoveryResponse(hopCount: nil)

    #expect(vm.discoveryResult == .noPathFound)
    #expect(vm.showDiscoveryResult == true)
    #expect(vm.isDiscovering == false)
  }

  @Test
  @MainActor
  func `Response signals contact refresh while discovering`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    vm.isDiscovering = true
    var refreshed = false
    vm.onContactNeedsRefresh = { refreshed = true }

    vm.handleDiscoveryResponse(hopCount: 1)

    #expect(refreshed)
  }

  @Test
  @MainActor
  func `Late response after timeout is ignored and does not flip the failure`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    // Timeout already fired: the failure alert is on screen and discovery ended.
    vm.isDiscovering = false
    vm.discoveryResult = .noPathFound
    vm.showDiscoveryResult = true
    var refreshed = false
    vm.onContactNeedsRefresh = { refreshed = true }

    vm.handleDiscoveryResponse(hopCount: 3)

    #expect(vm.showDiscoveryResult == true)
    #expect(vm.discoveryResult == .noPathFound)
    #expect(vm.isDiscovering == false)
    #expect(!refreshed)
  }

  @Test
  @MainActor
  func `Late response after cancel is ignored`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    vm.isDiscovering = true
    vm.cancelDiscovery()

    var refreshed = false
    vm.onContactNeedsRefresh = { refreshed = true }
    vm.handleDiscoveryResponse(hopCount: 3)

    #expect(vm.isDiscovering == false)
    #expect(vm.discoveryResult == nil)
    #expect(vm.showDiscoveryResult == false)
    #expect(!refreshed)
  }

  // MARK: - Test helpers

  private func makeSuiteDefaults() -> UserDefaults {
    UserDefaults(suiteName: "test.\(UUID().uuidString)")!
  }
}
