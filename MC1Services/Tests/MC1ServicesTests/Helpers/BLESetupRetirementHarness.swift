@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
@testable import MeshCore

@MainActor
struct BLESetupRetirementHarness {
  enum WaitError: Error { case expired }
  let machine: BLEStateMachine
  let adapter: iOSBLETransport
  let native: BLETransportHarness
  var peripheral: CBPeripheral {
    native.peripheral
  }

  var central: CBCentralManager {
    native.central
  }

  static func make() async -> Self {
    let native = BLETransportHarness(supportsWriteWithoutResponse: true)
    native.isReadyToWrite = true
    let machine = BLEStateMachine(centralManager: native.central)
    return Self(machine: machine, adapter: iOSBLETransport(stateMachine: machine), native: native)
  }

  func connect(binding: SessionTransportBinding) async throws -> SessionBoundTransport {
    let view = await adapter.makeSessionTransport(deviceID: peripheral.identifier, binding: binding)
    let connecting = Task { try await view.connect() }
    do {
      try await awaitCondition { await machine.currentPhase.name == "connecting" }
      await finishDiscovery()
      try await connecting.value
      return view
    } catch {
      await machine.shutdown()
      _ = await connecting.result
      throw error
    }
  }

  func beginDiscovery() async {
    native.peripheralState = .connected
    await machine.handleDidConnect(peripheral)
  }

  func finishDiscovery() async {
    await beginDiscovery()
    await machine.handleDidDiscoverServices(peripheral, error: nil)
    await machine.handleDidDiscoverCharacteristics(peripheral, service: native.service, error: nil)
    await machine.handleDidUpdateNotificationState(peripheral, characteristic: native.rx, error: nil)
  }

  func start(_ session: MeshCoreSession) async throws {
    let starting = Task { try await session.start(disconnectTransportOnFailure: false) }
    do {
      try await awaitCondition { await machine.setupWriteIsPending }
      await completeWrite()
      var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
      packet.append(Data(repeating: 1, count: 32))
      packet.append(Data(repeating: 0, count: 12))
      for value: UInt32 in [915_000, 125_000] {
        packet.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
      }
      packet.append(contentsOf: [7, 5])
      packet.append(contentsOf: "Retirement Radio".utf8)
      await inject(packet)
      try await starting.value
    } catch {
      await machine.shutdown()
      await session.stop(disconnectTransport: false)
      _ = await starting.result
      throw error
    }
  }

  func completeWrite() async {
    await machine.completeSetupWrite()
  }

  func inject(_ data: Data) async {
    await machine.injectSetupData(data)
  }

  func awaitCondition(_ predicate: @escaping @Sendable () async -> Bool) async throws {
    guard await observedBeforeDeadline(predicate) else { throw WaitError.expired }
  }

  func observedBeforeDeadline(_ predicate: @escaping @Sendable () async -> Bool) async -> Bool {
    do {
      try await waitUntil { await predicate() }
      return true
    } catch {
      return false
    }
  }
}

private extension BLEStateMachine {
  func completeSetupWrite() {
    guard case let .connected(peripheral, tx, _, _) = phase else { return }
    handleDidWriteValue(peripheral, characteristic: tx, error: nil, writeSequence: pendingWriteSequence)
  }

  func injectSetupData(_ data: Data) {
    guard case let .connected(_, _, _, continuation) = phase else { return }
    continuation.yield(data)
  }
}

extension BLEStateMachine {
  var setupWriteIsPending: Bool {
    pendingWriteContinuation != nil
  }
}
