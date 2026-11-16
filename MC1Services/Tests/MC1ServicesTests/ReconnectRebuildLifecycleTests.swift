import CoreBluetooth
import Foundation
@testable import MC1Services
@testable import MeshCore
import ObjectiveC
import Testing

@Suite("Reconnect Rebuild Lifecycle")
@MainActor
struct ReconnectRebuildLifecycleTests {
  @Test
  func `reconnect rebuild surfaces connected while its handshake is pending`() async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await harness.connect(.bluetooth)
      await harness.ble.holdReplies(for: .appStart)
      await harness.ble.simulateAutoReconnecting(deviceID: harness.deviceID)
      try await waitUntil("source loss should claim recovery") { harness.manager.reconnectionCoordinator.reconnectingDeviceID == harness.deviceID }
      await harness.ble.simulateReconnected()
      try await waitUntil("rebuilt handshake should preserve connected presentation") {
        let starts = await harness.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count
        return harness.manager.connectionState == .connected && starts == 2
      }
      await harness.ble.releaseReplies(for: .appStart)
      try await waitUntil("rebuilt stack should become ready") { harness.manager.connectionState == .ready }
      _ = try await harness.manager.session?.getTime()
      await harness.close()
    } catch { await harness.close(); throw error }
  }

  @Test
  func `bond loss during active reconnect surfaces guided recovery and connects again`() async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await harness.connect(.bluetooth)
      var surfaced: [UUID] = []
      harness.manager.onAuthenticationFailure = { surfaced.append($0) }
      await harness.ble.simulateAutoReconnecting(deviceID: harness.deviceID)
      try await waitUntil("recovery should own the radio") { harness.manager.reconnectionCoordinator.reconnectingDeviceID == harness.deviceID }
      await harness.ble.simulateRemoteLoss(error: .authenticationFailed)
      try await waitUntil("bond failure should reach guided recovery") {
        surfaced == [harness.deviceID] && harness.manager.connectionState == .disconnected
      }
      #expect(harness.manager.reconnectionCoordinator.reconnectingDeviceID == nil)
      guard case .operational = try await harness.connect(.bluetooth) else {
        Issue.record("a fresh connection should recover the radio")
        await harness.close()
        return
      }
      #expect(harness.manager.connectionState == .ready)
      _ = try await harness.manager.session?.getTime()
      await harness.close()
    } catch { await harness.close(); throw error }
  }

  @Test
  func `a stale-device disconnect during reconnect preserves the current cycle`() async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await harness.connect(.bluetooth)
      var surfaced: [UUID] = []
      harness.manager.onAuthenticationFailure = { surfaced.append($0) }
      await harness.ble.simulateAutoReconnecting(deviceID: harness.deviceID)
      try await waitUntil("recovery should own the radio") { harness.manager.reconnectionCoordinator.reconnectingDeviceID == harness.deviceID }
      let cycle = try #require(harness.manager.reconnectionCoordinator.currentCycle)
      let stale = BLELifecycleOrigin(deviceID: UUID(), generation: 1, eventTime: CFAbsoluteTimeGetCurrent())
      await harness.manager.handleBLELifecycleEvent(.disconnected(stale, error: .authenticationFailed))
      #expect(surfaced.isEmpty)
      #expect(harness.manager.connectionState == .connecting)
      #expect(harness.manager.reconnectionCoordinator.currentCycle == cycle)
      await harness.ble.simulateReconnected()
      try await waitUntil("current recovery should still become ready") { harness.manager.connectionState == .ready }
      _ = try await harness.manager.session?.getTime()
      await harness.close()
    } catch { await harness.close(); throw error }
  }

  @Test
  func `rebuild after a stopped predecessor completes over a refreshed stream`() async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await harness.connect(.bluetooth)
      let predecessor = try #require(harness.manager.session)
      await harness.ble.simulateAutoReconnecting(deviceID: harness.deviceID)
      try await waitUntil("predecessor should retire before rebuild") {
        harness.manager.reconnectionCoordinator.reconnectingDeviceID == harness.deviceID && harness.manager.connectionTeardownCount == 0
      }
      await harness.ble.simulateReconnected()
      try await waitUntil("replacement handshake and sync should finish") { harness.manager.connectionState == .ready }
      #expect(harness.manager.session !== predecessor)
      #expect(await harness.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count == 2)
      #expect(await harness.ble.connectCount == 1)
      #expect(await predecessor.subscriberCountForTest == 0)
      _ = try await harness.manager.session?.getTime()
      await harness.close()
    } catch { await harness.close(); throw error }
  }

  /// A renewal outside `.connected` is declined: the slot owner keeps its
  /// stream and the transport logs the declined phase instead of vending a
  /// stream no link feeds.
  @Test
  func `renewDataStream declines when the machine is not connected`() async {
    let sm = BLEStateMachine()

    let renewed = await sm.renewDataStream()

    #expect(renewed == nil)
    #expect(await sm.currentPhase.name == "idle")
  }

  /// Renewal keeps the phase `.connected` and must not tear down the RSSI
  /// keepalive the phase owns: a `transition(to:)`-based renewal would cancel
  /// it, since `cleanupPhaseResources` cancels the keepalive whenever it
  /// leaves `.connected`.
  @Test
  func `RSSI keepalive survives a data stream renewal`() async {
    let sm = BLEStateMachine()
    let peripheral = makeRebuildLeakedPeripheral()
    await sm.primeConnectedForRebuild(peripheral: peripheral)
    #expect(await sm.isRSSIKeepaliveActive)

    let renewed = await sm.renewDataStream()

    #expect(renewed != nil)
    #expect(await sm.isRSSIKeepaliveActive)
    #expect(await sm.currentPhase.name == "connected")

    await sm.shutdown()
  }

  /// The renewed stream is the one the `.connected` phase's continuation
  /// feeds, and data yielded after the swap arrives in yield order.
  @Test
  func `a renewed stream delivers subsequent data in order`() async throws {
    let sm = BLEStateMachine()
    let peripheral = makeRebuildLeakedPeripheral()
    await sm.primeConnectedForRebuild(peripheral: peripheral)

    let renewed = try #require(await sm.renewDataStream())
    guard case let .connected(_, _, _, continuation) = await sm.currentPhase else {
      Issue.record("expected .connected after renewal")
      return
    }
    continuation.yield(Data([1]))
    continuation.yield(Data([2]))
    continuation.yield(Data([3]))

    var received: [Data] = []
    for await chunk in renewed {
      received.append(chunk)
      if received.count == 3 { break }
    }
    #expect(received == [Data([1]), Data([2]), Data([3])])

    await sm.shutdown()
  }
}

/// A `CBPeripheral` double with a stable identity and `.connected` state.
private final class RebuildConnectedPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID()
  override var identifier: UUID {
    Self.uuid
  }

  override var state: CBPeripheralState {
    .connected
  }
}

/// Keeps raw-allocated peripheral doubles alive for the process lifetime so
/// CoreBluetooth teardown never runs on an instance that skipped its
/// designated initializer.
private enum RebuildPeripheralStore {
  nonisolated(unsafe) static var retained: [CBPeripheral] = []
}

/// Allocates a mock peripheral without invoking `CBPeripheral`'s unavailable
/// initializer and keeps it alive so it is never deallocated.
private func makeRebuildLeakedPeripheral() -> CBPeripheral {
  // swiftlint:disable:next force_cast
  let peripheral = class_createInstance(RebuildConnectedPeripheral.self, 0) as! CBPeripheral
  RebuildPeripheralStore.retained.append(peripheral)
  return peripheral
}

/// Installs `.connected` with a live keepalive on the real actor, mirroring
/// the restoration suite's prime seam, so renewal behavior is exercised on
/// the code under test rather than a mock.
private extension BLEStateMachine {
  func primeConnectedForRebuild(peripheral: CBPeripheral) {
    let tx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.txCharacteristic),
      properties: [.write],
      value: nil,
      permissions: [.writeable]
    )
    let rx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.rxCharacteristic),
      properties: [.notify],
      value: nil,
      permissions: [.readable]
    )
    let (_, continuation) = AsyncStream.makeStream(of: Data.self)
    phase = .connected(peripheral: peripheral, tx: tx, rx: rx, dataContinuation: continuation)
    phaseStartTime = Date()
    startRSSIKeepalive(for: peripheral)
  }
}
