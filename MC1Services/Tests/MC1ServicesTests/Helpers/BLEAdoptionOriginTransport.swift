import Foundation
@testable import MC1Services
import MeshCore

actor BLEAdoptionOriginTransport: iOSMeshTransport {
  let base: iOSBLETransport
  private var holdFirstOrigin = true
  private var originWaiter: CheckedContinuation<Void, Never>?
  private(set) var capturedOrigin: BLELifecycleOrigin?

  init(base: iOSBLETransport) {
    self.base = base
  }

  var originReadIsHeld: Bool {
    originWaiter != nil
  }

  func releaseOrigin() {
    originWaiter?.resume(); originWaiter = nil
  }

  var lifecycleOrigin: BLELifecycleOrigin? {
    get async {
      let origin = await base.lifecycleOrigin
      if holdFirstOrigin {
        holdFirstOrigin = false
        capturedOrigin = origin
        await withCheckedContinuation { originWaiter = $0 }
      }
      return origin
    }
  }

  var isConnected: Bool {
    get async { await base.isConnected }
  }

  var receivedData: AsyncStream<Data> {
    get async { await base.receivedData }
  }

  var supportsWriteWithoutResponse: Bool {
    get async { await base.supportsWriteWithoutResponse }
  }

  func makeSessionTransport(deviceID: UUID, binding: SessionTransportBinding) async -> SessionBoundTransport {
    await base.makeSessionTransport(deviceID: deviceID, binding: binding)
  }

  func connect() async throws {
    try await base.connect()
  }

  func connect(sessionBinding: SessionTransportBinding) async throws {
    try await base.connect(sessionBinding: sessionBinding)
  }

  func disconnect() async {
    await base.disconnect()
  }

  func disconnect(sessionBinding: SessionTransportBinding) async {
    await base.disconnect(sessionBinding: sessionBinding)
  }

  func cancelPendingEstablishment(sessionBinding: SessionTransportBinding) async {
    await base.cancelPendingEstablishment(sessionBinding: sessionBinding)
  }

  func cancelUnsubmittedWrites(sessionBinding: SessionTransportBinding) async {
    await base.cancelUnsubmittedWrites(sessionBinding: sessionBinding)
  }

  func send(_ data: Data) async throws {
    try await base.send(data)
  }

  func send(_ data: Data, sessionBinding: SessionTransportBinding) async throws {
    try await base.send(data, sessionBinding: sessionBinding)
  }

  func sendWithoutResponse(_ data: Data) async throws {
    try await base.sendWithoutResponse(data)
  }

  func sendWithoutResponse(_ data: Data, sessionBinding: SessionTransportBinding) async throws {
    try await base.sendWithoutResponse(data, sessionBinding: sessionBinding)
  }

  func receivedData(sessionBinding: SessionTransportBinding) async -> AsyncStream<Data> {
    await base.receivedData(sessionBinding: sessionBinding)
  }

  func refreshDataStream() async {
    await base.refreshDataStream()
  }

  func refreshDataStream(sessionBinding: SessionTransportBinding) async {
    await base.refreshDataStream(sessionBinding: sessionBinding)
  }

  func setLifecycleHandler(_ handler: @escaping @Sendable (BLELifecycleEvent) -> Void) async {
    await base.setLifecycleHandler(handler)
  }
}
