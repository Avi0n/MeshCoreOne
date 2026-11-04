@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
import Testing

@Suite("BLEStateMachine production operation lifetimes")
struct BLEStateMachineReliabilityTests {
  @Test(arguments: [false, true], [false, true])
  func `cancelled queued writer cannot strand its successor`(paced: Bool, firstTimesOut: Bool) async throws {
    let harness = BLETransportHarness()
    let clock = BLETestOperationClock()
    let pacing: TimeInterval = paced ? 1 : 0
    let machine = BLEStateMachine(writePacingDelay: pacing, centralManager: harness.central, operationClock: clock.clock)
    _ = try await harness.connect(machine)

    let first = Task { await result { try await machine.send(Data([1])) } }
    try #require(await harness.awaitCondition { harness.writePayloads.count == 1 && clock.sleeperCount == 1 })
    let cancelled = Task { await result { try await machine.send(Data([2])) } }
    try #require(await harness.awaitCondition { await machine.queuedWriteCount == 1 })
    let successor = Task { await result { try await machine.send(Data([3])) } }
    try #require(await harness.awaitCondition { await machine.queuedWriteCount == 2 })
    cancelled.cancel()

    if firstTimesOut {
      clock.advance(by: .seconds(5))
    } else {
      await harness.acknowledge(machine)
    }
    let cancelledResult = await cancelled.value
    #expect(cancelledResult.isCancellation)
    if paced {
      // The cancelled writer must hand its pacing obligation to the successor.
      _ = await harness.awaitCondition { clock.sleeperCount == 1 }
      clock.advance(by: .seconds(pacing))
    }
    let issued = await harness.awaitCondition { harness.writePayloads == [Data([1]), Data([3])] }
    #expect(issued, "The successor must issue without unrelated traffic or teardown")
    if issued {
      if firstTimesOut { await harness.acknowledge(machine) }
      await harness.acknowledge(machine)
    }
    if !issued { await machine.shutdown() }
    let firstResult = await first.value
    let successorResult = await successor.value
    await machine.shutdown()
    #expect(firstTimesOut ? firstResult.isTimeout : firstResult.isSuccess)
    #expect(successorResult.isSuccess)
    #expect(await machine.queuedWriteCount == 0)
  }

  @Test
  func `powered-off callback before wait still gives the wait a deadline`() async throws {
    let harness = BLETransportHarness()
    let clock = BLETestOperationClock()
    harness.power = .poweredOff
    let machine = BLEStateMachine(centralManager: harness.central, operationClock: clock.clock)
    await machine.handleCentralManagerDidUpdateState(.poweredOff)
    let waiter = Task { await result { try await machine.waitForPoweredOn() } }
    try #require(await harness.awaitCondition { await machine.currentPhase.kind == .waitingForBluetooth })
    let armed = await harness.awaitCondition { clock.sleeperCount == 1 }
    #expect(armed, "Entering the wait after an off callback must arm the existing grace")
    clock.advance(by: .seconds(1))
    let settled = await harness.awaitCondition { await machine.currentPhase.kind == .idle }
    #expect(settled, "The settled-off wait must fail without another state callback")
    await machine.shutdown()
    #expect(await waiter.value.isPoweredOff)
  }

  @Test(arguments: [false, true])
  func `off event after wait either expires or is rescued by power-on`(powersOn: Bool) async throws {
    let harness = BLETransportHarness()
    let clock = BLETestOperationClock()
    harness.power = .unknown
    let machine = BLEStateMachine(centralManager: harness.central, operationClock: clock.clock)
    let waiter = Task { await result { try await machine.waitForPoweredOn() } }
    try #require(await harness.awaitCondition { await machine.currentPhase.kind == .waitingForBluetooth })
    harness.power = .poweredOff
    await machine.handleCentralManagerDidUpdateState(.poweredOff)
    try #require(await harness.awaitCondition { clock.sleeperCount == 1 })
    if powersOn {
      harness.power = .poweredOn
      await machine.handleCentralManagerDidUpdateState(.poweredOn)
    }
    clock.advance(by: .seconds(1))
    let outcome = await waiter.value
    #expect(powersOn ? outcome.isSuccess : outcome.isPoweredOff)
    #expect(await machine.currentPhase.kind == .idle)
    #expect(clock.sleeperCount == 0)
    await machine.shutdown()
  }

  @Test
  func `cancelled power wait releases only its own wait lifetime`() async throws {
    let harness = BLETransportHarness()
    let clock = BLETestOperationClock()
    harness.power = .unknown
    let machine = BLEStateMachine(centralManager: harness.central, operationClock: clock.clock)
    let observation = OutcomeObservation()
    let old = Task {
      let outcome = await result { try await machine.waitForPoweredOn() }
      await observation.record(outcome)
      return outcome
    }
    try #require(await harness.awaitCondition { await machine.currentPhase.kind == .waitingForBluetooth })
    harness.power = .poweredOff
    await machine.handleCentralManagerDidUpdateState(.poweredOff)
    try #require(await harness.awaitCondition { clock.sleeperCount == 1 })
    old.cancel()
    let cancelled = await harness.awaitCondition { await observation.outcome != nil }
    #expect(cancelled, "Cancellation must release the power wait without another native callback")
    if !cancelled { await machine.shutdown() }
    #expect(await old.value.isCancellation)
    if !cancelled { return }
    #expect(await machine.currentPhase.kind == .idle)
    let successor = Task { await result { try await machine.waitForPoweredOn() } }
    try #require(await harness.awaitCondition { clock.sleeperCount == 1 })
    harness.power = .poweredOn
    await machine.handleCentralManagerDidUpdateState(.poweredOn)
    #expect(await successor.value.isSuccess)
    clock.advance(by: .seconds(1))
    #expect(await machine.currentPhase.kind == .idle)
    await machine.shutdown()
  }

  @Test
  func `cancelled write-command sender cannot write when readiness arrives`() async throws {
    let harness = BLETransportHarness(supportsWriteWithoutResponse: true)
    let machine = BLEStateMachine(centralManager: harness.central)
    _ = try await harness.connect(machine)
    let observation = OutcomeObservation()
    let sender = Task {
      let outcome = await result { try await machine.sendWithoutResponse(Data([1])) }
      await observation.record(outcome)
      return outcome
    }
    try #require(await harness.awaitCondition { await machine.isAwaitingWriteWithoutResponseReady })
    sender.cancel()
    harness.isReadyToWrite = true
    let bridge = await machine.delegateHandler
    bridge.peripheralIsReady(toSendWriteWithoutResponse: harness.peripheral)
    let cancelled = await harness.awaitCondition { await observation.outcome != nil }
    #expect(cancelled)
    if !cancelled { await machine.shutdown() }
    #expect(await sender.value.isCancellation)
    #expect(harness.writePayloads.isEmpty)
    await machine.shutdown()
  }

  @Test
  func `cancelled dequeued writer forwards pacing to the queued successor`() async throws {
    let harness = BLETransportHarness()
    let clock = BLETestOperationClock()
    let machine = BLEStateMachine(writePacingDelay: 1, centralManager: harness.central, operationClock: clock.clock)
    _ = try await harness.connect(machine)
    let first = Task { await result { try await machine.send(Data([1])) } }
    try #require(await harness.awaitCondition { harness.writePayloads.count == 1 && clock.sleeperCount == 1 })
    let cancelled = Task { await result { try await machine.send(Data([2])) } }
    try #require(await harness.awaitCondition { await machine.queuedWriteCount == 1 })
    let successor = Task { await result { try await machine.send(Data([3])) } }
    try #require(await harness.awaitCondition { await machine.queuedWriteCount == 2 })
    await harness.acknowledge(machine)
    try #require(await harness.awaitCondition { await machine.queuedWriteCount == 1 && clock.sleeperCount == 1 })
    cancelled.cancel()
    #expect(await cancelled.value.isCancellation)
    let handedOff = await harness.awaitCondition { clock.sleeperCount == 1 }
    #expect(handedOff)
    if handedOff { clock.advance(by: .seconds(1)) }
    let issued = await harness.awaitCondition { harness.writePayloads == [Data([1]), Data([3])] }
    #expect(issued)
    if issued { await harness.acknowledge(machine) } else { await machine.shutdown() }
    #expect(await first.value.isSuccess)
    #expect(await successor.value.isSuccess)
    await machine.shutdown()
  }

  @Test
  func `already-cancelled power wait cannot succeed on a powered-on central`() async {
    let harness = BLETransportHarness()
    let machine = BLEStateMachine(centralManager: harness.central)
    let cancelled = Task {
      withUnsafeCurrentTask { $0?.cancel() }
      return await result { try await machine.waitForPoweredOn() }
    }
    #expect(await cancelled.value.isCancellation)
    #expect(harness.connectCount == 0)
    await machine.shutdown()
  }

  private actor OutcomeObservation {
    var outcome: Outcome?
    func record(_ outcome: Outcome) {
      self.outcome = outcome
    }
  }

  private enum Outcome: Sendable {
    case success
    case cancellation
    case timeout
    case poweredOff
    case failure

    var isSuccess: Bool {
      if case .success = self { true } else { false }
    }

    var isCancellation: Bool {
      if case .cancellation = self { true } else { false }
    }

    var isTimeout: Bool {
      if case .timeout = self { true } else { false }
    }

    var isPoweredOff: Bool {
      if case .poweredOff = self { true } else { false }
    }
  }

  private func result(_ work: @Sendable () async throws -> Void) async -> Outcome {
    do { try await work(); return .success } catch is CancellationError { return .cancellation } catch {
      switch error as? BLEError {
      case .operationTimeout: return .timeout
      case .bluetoothPoweredOff: return .poweredOff
      default: return .failure
      }
    }
  }
}
