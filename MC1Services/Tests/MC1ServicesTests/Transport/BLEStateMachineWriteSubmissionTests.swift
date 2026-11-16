@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("BLE write submission")
struct BLEStateMachineWriteSubmissionTests {
  @Test
  func `a disconnected slot throws before writeValue and a dropped link resumes the pending write`() async throws {
    let harness = BLETransportHarness()
    let machine = BLEStateMachine(centralManager: harness.central)
    _ = try await harness.connect(machine)
    defer { Task { await machine.shutdown() } }

    harness.peripheralState = .disconnected
    await #expect(throws: MeshTransportError.notConnected) {
      try await machine.send(Data([0x01]))
    }
    #expect(harness.writePayloads.isEmpty)

    harness.peripheralState = .connected
    let pending = Task {
      try await machine.send(Data([0x02]))
    }
    let wrote = await harness.awaitCondition { harness.writePayloads == [Data([0x02])] }
    try #require(wrote)
    await machine.disconnect()

    let error = await #expect(throws: BLEError.self) {
      try await pending.value
    }
    guard case .notConnected? = error else {
      Issue.record("Expected the pending write to resume notConnected, got \(String(describing: error))")
      return
    }
  }
}
