#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Removal confirmation identity boundaries")
@MainActor
struct ConnectionRemovalConfirmationTests {
  @Test
  func `reset cleanup retains its best effort system removal policy`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await env.connect(.bluetooth)
      let device = try #require(env.manager.connectedDevice)
      let context = try #require(env.manager.captureCurrentRemoval())
      env.pairing.isSessionActive = true
      env.pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: device.id, displayName: "Reset radio")])
      env.pairing.removeAccessoryError = DevicePairingError.cancelled
      let result = await env.manager.forgetDeviceAfterReset(context)
      if case let .removed(receipt) = result {
        #expect(env.manager.isCurrent(receipt))
      } else {
        Issue.record("After a submitted factory reset, local cleanup remains best effort across system removal failure")
      }
      #expect(try await env.manager.persistenceStore.fetchDevice(id: device.id) == nil)
      let disconnected = env.manager.currentConnection == nil
      #expect(disconnected)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `confirmation uses identity acquired while system sheet is open`(declined: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let saved = DeviceDTO.testDevice(publicKey: env.wifi.publicKey, isActive: false).copy {
      $0.connectionMethods = [.wifi(host: "radio.test", port: 5123, displayName: nil)]
    }
    let gate = Gate()
    var connecting: Task<ConnectionResult, Error>?
    var removing: Task<Void, Error>?
    do {
      try await env.manager.persistenceStore.saveDevice(saved)
      env.pairing.isSessionActive = true
      env.pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: saved.id, displayName: "Radio")])
      env.pairing.beforeRemovalResult = { await gate.wait() }
      if declined { env.pairing.removeAccessoryError = DevicePairingError.cancelled }
      await env.wifi.holdReplies(for: .appStart)
      let connect = Task { try await env.connect(.wifi) }
      connecting = connect
      try await waitUntil { await env.wifi.sentData.contains { $0.first == CommandCode.appStart.rawValue } }
      let removal = Task { try await env.manager.deleteDevice(id: saved.id) }
      removing = removal
      try await waitUntil { gate.entered }
      await env.wifi.releaseAllGates()
      let result = try await connect.value
      #expect(env.manager.isCurrent(result))
      #expect(try await env.manager.session?.getTime() != nil)
      gate.release()
      if declined {
        await #expect(throws: DevicePairingError.self) { try await removal.value }
        #expect(env.manager.isCurrent(result))
        #expect(try await env.manager.persistenceStore.fetchDevice(id: saved.id)?.isActive == true)
        #expect(env.manager.connectionIntent.wantsConnection)
      } else {
        try await removal.value
        let disconnected = env.manager.currentConnection == nil
        #expect(disconnected)
        #expect(try await env.manager.persistenceStore.fetchDevice(id: saved.id) == nil)
        #expect(!env.manager.connectionIntent.wantsConnection)
      }
    } catch {
      gate.release()
      await env.wifi.releaseAllGates()
      _ = await connecting?.result
      _ = await removing?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `confirmation resolves the fresh rekeyed row`(declined: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let saved = DeviceDTO.testDevice(publicKey: env.ble.publicKey, isActive: false).copy {
      $0.connectionMethods = [.bluetooth(peripheralUUID: $0.id, displayName: nil),
                              .wifi(host: "radio.test", port: 5123, displayName: nil)]
    }
    let gate = Gate()
    var connecting: Task<ConnectionResult, Error>?
    var removing: Task<Void, Error>?
    do {
      try await env.manager.persistenceStore.saveDevice(saved)
      env.pairing.isSessionActive = true
      env.pairing.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: saved.id, displayName: "Previous association"),
        ASAccessory(bluetoothIdentifier: env.deviceID, displayName: "Current association")
      ])
      env.pairing.beforeRemovalResult = { await gate.wait() }
      if declined { env.pairing.removeAccessoryError = DevicePairingError.cancelled }
      await env.ble.holdReplies(for: .getAutoAddConfig)
      let connect = Task { try await env.connect(.bluetooth) }
      connecting = connect
      try await waitUntil { await env.ble.sentData.contains { $0.first == CommandCode.getAutoAddConfig.rawValue } }
      let removal = Task { try await env.manager.deleteDevice(id: saved.id) }
      removing = removal
      try await waitUntil { gate.entered }
      await env.ble.releaseAllGates()
      let result = try await connect.value
      #expect(env.manager.isCurrent(result))
      let rekeyed = try #require(try await env.manager.persistenceStore.fetchDevice(id: env.deviceID))
      #expect(rekeyed.radioID == saved.radioID)
      #expect(try await env.manager.persistenceStore.fetchDevice(id: saved.id) == nil)
      gate.release()
      if declined {
        await #expect(throws: DevicePairingError.self) { try await removal.value }
        #expect(env.manager.isCurrent(result))
        #expect(try await env.manager.persistenceStore.fetchDevice(id: env.deviceID)?.isActive == true)
        let observedTime = try await #require(env.manager.session).getTime()
        #expect(observedTime.timeIntervalSince1970 > 0)
      } else {
        try await removal.value
        let disconnected = env.manager.currentConnection == nil
        #expect(disconnected)
        #expect(!env.manager.connectionIntent.wantsConnection)
        let ghost = try #require(try await env.manager.persistenceStore.fetchDevice(publicKey: saved.publicKey))
        #expect(ghost.radioID == saved.radioID)
        #expect(ghost.connectionMethods.isEmpty)
        #expect(!ghost.isActive)
        #expect(try await env.manager.isCurrent(env.connect(.bluetooth)))
        let observedTime = try await #require(env.manager.session).getTime()
        #expect(observedTime.timeIntervalSince1970 > 0)
      }
    } catch {
      gate.release()
      await env.ble.releaseAllGates()
      _ = await connecting?.result
      _ = await removing?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `accepted old system removal preserves new request`(sameRadio: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness(sameRadio: false)
    let gate = Gate()
    var removing: Task<Void, Error>?
    do {
      _ = try await env.connect(.wifi)
      let first = try #require(env.manager.currentConnection)
      env.pairing.isSessionActive = true
      env.pairing.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: first.device.id, displayName: "First"),
        ASAccessory(bluetoothIdentifier: env.deviceID, displayName: "Next")
      ])
      env.pairing.beforeRemovalResult = { await gate.wait() }
      let removal = Task { try await env.manager.deleteDevice(id: first.device.id) }
      removing = removal
      try await waitUntil { gate.entered }
      let next = try await env.connect(sameRadio ? .wifi : .bluetooth)
      let successor = try #require(env.manager.currentConnection)
      #expect(successor.requestID != first.requestID)
      gate.release()
      try await removal.value
      #expect(env.manager.isCurrent(next))
      #expect(try await env.manager.persistenceStore.fetchDevice(id: successor.device.id)?.radioID == successor.device.radioID)
      let observedTime = try await successor.services.session.getTime()
      #expect(observedTime.timeIntervalSince1970 > 0)
    } catch {
      gate.release()
      _ = await removing?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test(arguments: [TransportType.bluetooth, .wifi])
  func `native association removal during preparation preserves only surviving wi fi`(transport: TransportType) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let saved = DeviceDTO.testDevice(id: env.deviceID, publicKey: env.ble.publicKey, isActive: false).copy {
      $0.connectionMethods = [
        .bluetooth(peripheralUUID: env.deviceID, displayName: nil),
        .wifi(host: "radio.test", port: 5123, displayName: nil)
      ]
    }
    let radio = transport == .bluetooth ? env.ble : env.wifi
    var connecting: Task<ConnectionResult, Error>?
    do {
      try await env.manager.persistenceStore.saveDevice(saved)
      env.pairing.isSessionActive = true
      env.pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: env.deviceID, displayName: "Radio")])
      await radio.holdReplies(for: .getAutoAddConfig)
      let connect = Task { try await env.connect(transport) }
      connecting = connect
      try await waitUntil { await radio.sentData.contains { $0.first == CommandCode.getAutoAddConfig.rawValue } }
      env.pairing.simulateAccessoryRemoved(env.deviceID)
      await env.manager.metadataCommitTask?.value
      await radio.releaseAllGates()
      let result = await connect.result
      if transport == .wifi {
        #expect(try env.manager.isCurrent(result.get()))
        #expect(try await env.manager.session?.getTime() != nil)
      } else {
        let disconnected = env.manager.currentConnection == nil
        #expect(disconnected)
      }
      let kept = try #require(try await env.manager.persistenceStore.fetchDevice(id: saved.id))
      #expect(kept.radioID == saved.radioID)
      let onlyWiFiRemains = kept.connectionMethods.allSatisfy(\.isWiFi)
      #expect(onlyWiFiRemains)
    } catch {
      await radio.releaseAllGates()
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
