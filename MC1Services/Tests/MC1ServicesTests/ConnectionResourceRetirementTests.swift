import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Connection resource retirement")
@MainActor
struct ConnectionResourceRetirementTests {
  @Test
  func `retirement closes its captured socket before joining a blocked physical send`() async throws {
    let socket = DisconnectReleasedSocket()
    let binding = SessionTransportBinding()
    let session = MeshCoreSession(transport: SessionBoundTransport(transport: socket, binding: binding))
    let resource = ConnectionResource(binding: binding, transport: socket, session: session, wifi: socket, ble: nil)
    do {
      try await session.start(disconnectTransportOnFailure: false)
      await socket.holdNextSend()
      let waiter = Task { try await session.getTime() }
      try await waitUntil("physical send should remain active") { await socket.isSendHeld }
      waiter.cancel()
      await #expect(throws: CancellationError.self) { try await waiter.value }
      #expect(await socket.isSendHeld)
      #expect(await socket.disconnectCount == 0)

      let retirement = Task { await resource.retire(closePhysical: true) }
      try await waitUntil("captured socket close should release the actual send") {
        let closes = await socket.disconnectCount
        let held = await socket.isSendHeld
        return closes == 1 && !held
      }
      await retirement.value
      #expect(!binding.isActive)
      #expect(await session.subscriberCountForTest == 0)

      let successorBinding = SessionTransportBinding()
      let successor = MeshCoreSession(transport: SessionBoundTransport(transport: socket, binding: successorBinding))
      try await successor.start(disconnectTransportOnFailure: false)
      _ = try await successor.getTime()
      successorBinding.revoke()
      await successor.stop(disconnectTransport: false, drainExchanges: true)
      await socket.disconnect()
      await successorBinding.waitForQuiescence()
      await successor.waitForQuiescence()
    } catch {
      binding.revoke()
      await socket.disconnect()
      await session.stop(disconnectTransport: false)
      await binding.waitForQuiescence()
      await session.waitForQuiescence()
      throw error
    }
  }

  private actor DisconnectReleasedSocket: WiFiMeshTransport {
    private let radio = ConnectionTestRadioTransport()
    private var holdSend = false
    private var heldSend: CheckedContinuation<Void, Never>?
    private(set) var disconnectCount = 0
    var isSendHeld: Bool {
      heldSend != nil
    }

    var connectionInfo: (host: String, port: UInt16)? {
      ("radio.test", 5123)
    }

    var isConnected: Bool {
      get async { await radio.isConnected }
    }

    var receivedData: AsyncStream<Data> {
      get async { await radio.receivedData }
    }

    func setConnectionInfo(host: String, port: UInt16) {}
    func setDisconnectionHandler(_ handler: @escaping @Sendable (Error?, CFAbsoluteTime) -> Void) {}
    func connect() async throws {
      try await radio.connect()
    }

    func holdNextSend() {
      holdSend = true
    }

    func send(_ data: Data) async throws {
      try await radio.send(data)
      if holdSend {
        holdSend = false
        await withCheckedContinuation { heldSend = $0 }
      }
    }

    func disconnect() async {
      disconnectCount += 1
      let held = heldSend
      heldSend = nil
      held?.resume()
      await radio.disconnect()
    }
  }
}
