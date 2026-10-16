#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation

/// Test-injection seam for `AccessorySetupKitService`. Surfaces the methods and
/// observable properties that `ConnectionManager` calls on the picker service so
/// tests can substitute a mock that drives the picker outcomes synthetically.
///
/// Conformance does not change the runtime semantics of `AccessorySetupKitService`;
/// it only narrows the type used by `ConnectionManager` from the concrete class to
/// a protocol.
@MainActor
protocol AccessorySetupKitServicing: AnyObject {
  var pairedAccessories: [ASAccessory] { get }
  var isSessionActive: Bool { get }
  var delegate: AccessorySetupKitServiceDelegate? { get set }
  func activateSession() async throws
  func showPicker() async throws -> UUID
  func removeAccessory(_ accessory: ASAccessory) async throws
  func renameAccessory(_ accessory: ASAccessory) async throws
  func accessory(for bluetoothID: UUID) -> ASAccessory?
  func invalidateSession()
}

extension AccessorySetupKitService: AccessorySetupKitServicing {}
