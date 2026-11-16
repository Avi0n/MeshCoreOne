import Foundation
import MeshCore

/// Extends `MeshTransport` with the iOS-specific surface that `ConnectionManager`
/// invokes on `iOSBLETransport`. Lifting this surface into a protocol gives test
/// targets a place to inject a mock transport while leaving the platform-agnostic
/// `MeshTransport` contract unchanged.
public protocol iOSMeshTransport: MeshTransport {
  func makeSessionTransport(deviceID: UUID, binding: SessionTransportBinding) async -> SessionBoundTransport
  func cancelPendingEstablishment(sessionBinding: SessionTransportBinding) async
  func cancelUnsubmittedWrites(sessionBinding: SessionTransportBinding) async
  func setLifecycleHandler(_ handler: @escaping @Sendable (BLELifecycleEvent) -> Void) async
  var lifecycleOrigin: BLELifecycleOrigin? { get async }

  /// Re-vends `receivedData` for a session rebuild over the existing link.
  /// A stopped predecessor session's receive-loop cancellation terminates the
  /// vended stream's shared storage, so without a refresh the next session
  /// iterates a dead stream and its handshake times out.
  func refreshDataStream() async
  func refreshDataStream(sessionBinding: SessionTransportBinding) async
}

extension iOSBLETransport: iOSMeshTransport {}
