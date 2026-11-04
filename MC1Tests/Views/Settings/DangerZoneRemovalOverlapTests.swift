#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Danger Zone overlapping removal ownership")
@MainActor
struct DangerZoneRemovalOverlapTests {
  @Test
  func `superseded reset cleanup cannot replace current forget admission`() async throws {
    let env = try await ConnectionLinkSwitchHarness.make(foreignWiFiRadio: true)
    let model = DangerZoneViewModel()
    model.configure(
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice }, connectionManager: env.manager
    )
    let gate = Gate()
    var resetting: Task<Bool, Never>?
    var connecting: Task<ConnectionResult, Error>?
    var forgetting: Task<Bool, Never>?
    var heldOperationID: UUID?
    var removalsAtForgetEntry = 0
    do {
      let source = try #require(env.manager.currentConnection)
      let successorID = UUID()
      let successor = env.device.copy {
        $0.id = successorID
        $0.radioID = UUID()
        $0.publicKey = Data(repeating: 0xB2, count: ProtocolLimits.publicKeySize)
        $0.nodeName = "Successor"
        $0.isActive = false
        $0.connectionMethods = [
          .bluetooth(peripheralUUID: successorID, displayName: "Successor"),
          .wifi(host: "successor.radio", port: 4242, displayName: nil)
        ]
      }
      try await env.manager.persistenceStore.saveDevice(successor)
      env.pairing.isSessionActive = true
      env.pairing.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: source.device.id, displayName: "Source"),
        ASAccessory(bluetoothIdentifier: successor.id, displayName: "Successor")
      ])
      env.pairing.beforeRemovalResult = {
        if env.pairing.lastRemovedDeviceID == successor.id {
          heldOperationID = env.manager.removalOperationID
          removalsAtForgetEntry = env.pairing.removeAccessoryCallCount
          await gate.wait()
        }
      }
      await env.wifi.setConnectError(nil)
      await env.bluetooth.holdNextSendCompletion()
      let reset = Task { await model.factoryReset() }
      resetting = reset
      try await waitUntil("source reset should remain at its actual physical completion") {
        let held = await env.bluetooth.sendCompletionIsHeld
        let frames = await env.bluetooth.sentData
        return held && frames.contains { $0.first == CommandCode.factoryReset.rawValue }
      }
      let connect = Task { try await env.manager.connectViaWiFi(host: "successor.radio", port: 4242) }
      connecting = connect
      try await waitUntil("foreign WiFi request should supersede the reset source") {
        env.manager.connectionRequestID != source.requestID
      }
      await env.bluetooth.releaseSendCompletion()
      #expect(try await env.manager.isCurrent(connect.value))
      let current = try #require(env.manager.currentConnection)
      #expect(current.device.id == successor.id)
      #expect(current.device.radioID == successor.radioID)
      #expect(current.requestID != source.requestID)
      try #require(model.isResetting)
      let forget = Task { await model.forgetDevice(deleteData: true) }
      forgetting = forget
      try await waitUntil("current forget should hold its real ASK confirmation") { gate.entered }
      try #require(removalsAtForgetEntry == 1, "source reset cleanup must start after current forget admission")
      let operation = try #require(heldOperationID)
      #expect(await reset.value == false)
      #expect(env.manager.isCurrent(current))
      #expect(env.manager.removalOperationID == operation)
      #expect(try await env.manager.persistenceStore.fetchDevice(id: current.device.id) != nil)
      #expect(model.errorMessage == nil)
      let time = try await current.services.session.getTime()
      #expect(time.timeIntervalSince1970 > 0)
      gate.release()
      #expect(await forget.value)
      #expect(env.manager.currentConnection == nil)
      #expect(try await env.manager.persistenceStore.fetchDevice(id: current.device.id) == nil)
      #expect(try await env.manager.persistenceStore.fetchDevice(radioID: current.device.radioID) == nil)
      #expect(model.errorMessage == nil)
    } catch {
      gate.release()
      env.pairing.beforeRemovalResult = nil
      await env.bluetooth.releaseAllGates()
      await env.wifi.releaseAllGates()
      _ = await resetting?.value
      _ = await connecting?.result
      _ = await forgetting?.value
      await env.close()
      throw error
    }
    env.pairing.beforeRemovalResult = nil
    await env.close()
  }

  @MainActor
  private final class Gate {
    private(set) var entered = false
    private var released = false
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
      entered = true
      guard !released else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func release() {
      released = true
      continuation?.resume()
      continuation = nil
    }
  }
}
