#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services

/// In-memory ASK picker used by pairing tests. `showPicker` returns the configured
/// `pickerResult` either immediately or after the test releases a continuation,
/// letting tests pin pairing in specific suspension points.
@MainActor
public final class MockAccessorySetupKitService: AccessorySetupKitServicing {
  public var pairedAccessories: [ASAccessory] = []
  public var isSessionActive: Bool = true
  public weak var delegate: AccessorySetupKitServiceDelegate?

  public private(set) var removeAccessoryCallCount = 0
  public private(set) var lastRemovedDeviceID: UUID?
  public private(set) var renameAccessoryCallCount = 0
  public private(set) var activateSessionCallCount = 0
  public private(set) var invalidateSessionCallCount = 0

  /// Result for the next `showPicker()` call. Tests configure this before invoking
  /// pairing flows.
  public var pickerResult: Result<UUID, Error> = .failure(AccessorySetupKitError.sessionNotActive)

  /// Optional gate: when non-nil, `showPicker` awaits this stream's first element
  /// before returning `pickerResult`. Used to pin the awaiting Task in suspension.
  public var pickerGate: AsyncStream<Void>?

  /// Optional signal: when non-nil, `showPicker` yields to this continuation as
  /// soon as it enters, before awaiting `pickerGate`. Tests await this signal to
  /// know deterministically that the pair task has reached the picker await.
  public var pickerEnteredSignal: AsyncStream<Void>.Continuation?

  /// When non-nil, `removeAccessory` records the attempt and then throws this,
  /// modelling the user declining the system removal confirmation so the
  /// accessory stays registered.
  public var removeAccessoryError: Error?

  public init() {}

  public func setPickerResult(_ result: Result<UUID, Error>) {
    pickerResult = result
  }

  public func setPairedAccessories(_ accessories: [ASAccessory]) {
    pairedAccessories = accessories
  }

  public func activateSession() async throws {
    activateSessionCallCount += 1
  }

  public func showPicker() async throws -> UUID {
    pickerEnteredSignal?.yield()
    if let gate = pickerGate {
      for await _ in gate {
        break
      }
    }
    switch pickerResult {
    case let .success(id):
      return id
    case let .failure(error):
      throw error
    }
  }

  public func removeAccessory(_ accessory: ASAccessory) async throws {
    removeAccessoryCallCount += 1
    lastRemovedDeviceID = accessory.bluetoothIdentifier
    if let removeAccessoryError {
      throw removeAccessoryError
    }
    pairedAccessories.removeAll { $0.bluetoothIdentifier == accessory.bluetoothIdentifier }
  }

  public func renameAccessory(_ accessory: ASAccessory) async throws {
    renameAccessoryCallCount += 1
  }

  public func accessory(for bluetoothID: UUID) -> ASAccessory? {
    pairedAccessories.first { $0.bluetoothIdentifier == bluetoothID }
  }

  public func invalidateSession() {
    invalidateSessionCallCount += 1
  }
}
