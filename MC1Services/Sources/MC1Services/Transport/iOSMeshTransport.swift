import Foundation
import MeshCore

/// Extends `MeshTransport` with the iOS-specific surface that `ConnectionManager`
/// invokes on `iOSBLETransport`. Lifting this surface into a protocol gives test
/// targets a place to inject a mock transport while leaving the platform-agnostic
/// `MeshTransport` contract unchanged.
public protocol iOSMeshTransport: MeshTransport {
  func setDeviceID(_ id: UUID) async
  func switchDevice(to deviceID: UUID) async throws
  func setDisconnectionHandler(_ handler: @escaping @Sendable (UUID, Error?) -> Void) async
  func setReconnectionHandler(_ handler: @escaping @Sendable (UUID) -> Void) async
}

extension iOSBLETransport: iOSMeshTransport {}
