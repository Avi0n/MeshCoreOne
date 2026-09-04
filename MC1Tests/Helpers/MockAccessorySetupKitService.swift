#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services

/// In-memory ASK picker for app-layer pairing tests. Mirrors the MC1Services test mock.
@MainActor
final class MockAccessorySetupKitService: AccessorySetupKitServicing {
  private var storedPairedAccessories: [ASAccessory] = []
  private var hasActivatedSession = false

  /// Empty until `activateSession`. Lookup uses the backing store so `removeDevice` works without activate.
  var pairedAccessories: [ASAccessory] {
    hasActivatedSession ? storedPairedAccessories : []
  }

  var isSessionActive: Bool = true
  weak var delegate: AccessorySetupKitServiceDelegate?

  private(set) var removeAccessoryCallCount = 0
  private(set) var lastRemovedDeviceID: UUID?
  private(set) var activateSessionCallCount = 0
  private(set) var showPickerCallCount = 0

  var pickerResult: Result<UUID, Error> = .failure(AccessorySetupKitError.sessionNotActive)
  var removeAccessoryError: Error?

  func setPickerResult(_ result: Result<UUID, Error>) {
    pickerResult = result
  }

  func setPairedAccessories(_ accessories: [ASAccessory]) {
    storedPairedAccessories = accessories
  }

  func activateSession() async throws {
    activateSessionCallCount += 1
    hasActivatedSession = true
  }

  func showPicker() async throws -> UUID {
    showPickerCallCount += 1
    switch pickerResult {
    case let .success(id):
      return id
    case let .failure(error):
      throw error
    }
  }

  func removeAccessory(_ accessory: ASAccessory) async throws {
    removeAccessoryCallCount += 1
    lastRemovedDeviceID = accessory.bluetoothIdentifier
    if let removeAccessoryError {
      throw removeAccessoryError
    }
    storedPairedAccessories.removeAll { $0.bluetoothIdentifier == accessory.bluetoothIdentifier }
  }

  func renameAccessory(_ accessory: ASAccessory) async throws {}

  func accessory(for bluetoothID: UUID) -> ASAccessory? {
    storedPairedAccessories.first { $0.bluetoothIdentifier == bluetoothID }
  }

  func invalidateSession() {
    hasActivatedSession = false
  }
}
