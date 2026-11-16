#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Danger Zone removal ownership")
@MainActor
struct DangerZoneRemovalTests {
  enum ForgetOutcome: CaseIterable {
    case owned, declined, ownedFailure, superseded, supersededFailure

    var superseded: Bool {
      self == .superseded || self == .supersededFailure
    }

    var failed: Bool {
      self == .ownedFailure || self == .supersededFailure
    }
  }

  @Test(arguments: ForgetOutcome.allCases, [false, true])
  func `forget confirmation retains its source outcome`(outcome: ForgetOutcome, deleteData: Bool) async throws {
    let env = try await ConnectionLinkSwitchHarness.make(foreignWiFiRadio: true)
    let model = DangerZoneViewModel()
    model.configure(
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice }, connectionManager: env.manager
    )
    let gate = Gate()
    var forgetting: Task<Bool, Never>?
    do {
      env.pairing.isSessionActive = true
      env.pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: env.device.id, displayName: "Source")])
      env.pairing.beforeRemovalResult = { await gate.wait() }
      if outcome == .declined { env.pairing.removeAccessoryError = DevicePairingError.cancelled }
      if outcome.failed { env.pairing.removeAccessoryError = NSError(domain: "RemovalSystem", code: 1) }
      let forget = Task { await model.forgetDevice(deleteData: deleteData) }
      forgetting = forget
      try await waitUntil { gate.entered }
      if outcome.superseded {
        await env.wifi.setConnectError(nil)
        #expect(try await env.manager.isCurrent(env.manager.connectViaWiFi(host: "successor.radio", port: 4242)))
      }
      let current = try #require(env.manager.currentConnection)
      gate.release()
      #expect(await forget.value == (outcome == .owned))
      #expect((model.errorMessage != nil) == (outcome == .ownedFailure))
      if outcome == .owned {
        let disconnected = env.manager.currentConnection == nil
        #expect(disconnected)
        #expect(try await env.manager.persistenceStore.fetchDevice(id: env.device.id) == nil)
      } else {
        #expect(env.manager.isCurrent(current))
        #expect(try await env.manager.persistenceStore.fetchDevice(id: current.device.id)?.radioID == current.device.radioID)
        let observedTime = try await current.services.session.getTime()
        #expect(observedTime.timeIntervalSince1970 > 0)
      }
    } catch {
      gate.release()
      _ = await forgetting?.value
      await env.close()
      throw error
    }
    env.pairing.beforeRemovalResult = nil
    await env.close()
  }

  @Test
  func `forget metadata finishes before successor without dismissing it`() async throws {
    let env = try await ConnectionLinkSwitchHarness.make(foreignWiFiRadio: true)
    let model = DangerZoneViewModel()
    model.configure(
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice }, connectionManager: env.manager
    )
    let gate = Gate()
    var forgetting: Task<Bool, Never>?
    var connecting: Task<ConnectionResult, Error>?
    let sourceID = env.device.id
    let accepted = env.manager.enqueueMetadata { store in
      await gate.wait()
      try await store.updateConnectionDevice(id: sourceID) { $0.copy { $0.nodeName = "Accepted before removal" } }
    }
    do {
      try await waitUntil { gate.entered }
      let forget = Task { await model.forgetDevice(deleteData: false) }
      forgetting = forget
      try await waitUntil { env.manager.currentConnection == nil }
      let terminalID = env.manager.connectionRequestID
      await env.wifi.setConnectError(nil)
      let connect = Task { try await env.manager.connectViaWiFi(host: "successor.radio", port: 4242) }
      connecting = connect
      try await waitUntil { env.manager.connectionRequestID != terminalID }
      #expect(await env.wifi.connectCount == env.initialWiFiAttempts)
      gate.release()
      try await accepted.value
      #expect(await forget.value == false)
      #expect(try await env.manager.isCurrent(connect.value))
      #expect(model.errorMessage == nil)
      let ghost = try #require(try await env.manager.persistenceStore.fetchDevice(publicKey: env.device.publicKey))
      #expect(ghost.radioID == env.device.radioID)
      #expect(ghost.nodeName == "Accepted before removal")
      #expect(ghost.connectionMethods.isEmpty)
      let observedTime = try await #require(env.manager.session).getTime()
      #expect(observedTime.timeIntervalSince1970 > 0)
    } catch {
      gate.release()
      _ = await accepted.result
      _ = await forgetting?.value
      _ = await connecting?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `reset completion cannot retire same request replacement link`(loseSuccessor: Bool) async throws {
    let env = try await ConnectionLinkSwitchHarness.make()
    let model = DangerZoneViewModel()
    model.configure(
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice }, connectionManager: env.manager
    )
    var resetting: Task<Bool, Never>?
    do {
      let source = try #require(env.manager.currentConnection)
      await env.bluetooth.setConnectError(nil)
      await env.bluetooth.holdNextSendCompletion()
      let reset = Task { await model.factoryReset() }
      resetting = reset
      try await waitUntil { await env.bluetooth.sentData.contains { $0.first == CommandCode.factoryReset.rawValue } }
      await env.bluetooth.simulateAutoReconnecting(deviceID: source.device.id)
      try await waitUntil { env.manager.currentConnection == nil }
      await env.bluetooth.releaseSendCompletion()
      try await waitUntil { env.manager.connectionTeardownCount == 0 }
      await env.bluetooth.simulateReconnected()
      try await waitUntil {
        env.manager.connectionState == .ready && env.manager.currentConnection?.generation != source.generation
      }
      let successor = try #require(env.manager.currentConnection)
      #expect(successor.requestID == source.requestID)
      if loseSuccessor {
        await env.bluetooth.simulateAutoReconnecting(deviceID: successor.device.id)
        try await waitUntil { env.manager.currentConnection == nil }
        try await waitUntil { env.manager.connectionTeardownCount == 0 }
      }
      #expect(await reset.value == false)
      let requestSurvives = env.manager.connectionRequestID == source.requestID
      #expect(requestSurvives)
      #expect(try await env.manager.persistenceStore.fetchDevice(id: successor.device.id) != nil)
      if loseSuccessor, requestSurvives {
        await env.bluetooth.simulateReconnected()
        try await waitUntil {
          env.manager.connectionState == .ready && env.manager.currentConnection?.generation != successor.generation
        }
        let observedTime = try await #require(env.manager.session).getTime()
        #expect(observedTime.timeIntervalSince1970 > 0)
      } else if !loseSuccessor {
        let successorIsCurrent = env.manager.isCurrent(successor)
        #expect(successorIsCurrent)
        let observedTime = try await successor.services.session.getTime()
        #expect(observedTime.timeIntervalSince1970 > 0)
      }
    } catch {
      await env.bluetooth.releaseAllGates()
      _ = await resetting?.value
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `factory reset cannot forget successor`() async throws {
    let env = try await ConnectionLinkSwitchHarness.make(foreignWiFiRadio: true)
    let model = DangerZoneViewModel()
    model.configure(
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice }, connectionManager: env.manager
    )
    var resetting: Task<Bool, Never>?
    var connecting: Task<ConnectionResult, Error>?
    do {
      await env.wifi.setConnectError(nil)
      await env.bluetooth.holdReplies(for: .factoryReset)
      let reset = Task { await model.factoryReset() }
      resetting = reset
      try await waitUntil { await env.bluetooth.sentData.contains { $0.first == CommandCode.factoryReset.rawValue } }
      let connect = Task { try await env.manager.connectViaWiFi(host: "successor.radio", port: 4242) }
      connecting = connect
      try await waitUntil { env.manager.connectionRequestID != env.manager.currentConnection?.requestID }
      await env.bluetooth.releaseReplies(for: .factoryReset)
      let result = try await connect.value
      #expect(env.manager.isCurrent(result))
      let successor = try #require(env.manager.currentConnection)
      let observedTime = try await successor.services.session.getTime()
      #expect(observedTime.timeIntervalSince1970 > 0)
      #expect(await reset.value == false, "A superseded reset cannot dismiss the successor page")
      let successorIsCurrent = env.manager.isCurrent(successor)
      #expect(successorIsCurrent)
      #expect(try await env.manager.persistenceStore.fetchDevice(id: successor.device.id) != nil)
    } catch {
      await env.bluetooth.releaseAllGates()
      await env.wifi.releaseAllGates()
      _ = await resetting?.value
      _ = await connecting?.result
      await env.close()
      throw error
    }
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
