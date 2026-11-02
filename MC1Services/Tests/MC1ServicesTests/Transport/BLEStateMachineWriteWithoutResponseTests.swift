@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
import Testing

/// Covers the `.withoutResponse` readiness continuation and capability capture.
/// Production send admission and native origins are exercised by the transport harness.
@Suite("BLEStateMachine write-without-response path")
struct BLEStateMachineWriteWithoutResponseTests {
  // MARK: - Helpers

  /// Polls until the state machine has installed its readiness continuation, so the test
  /// can deterministically signal readiness without racing the suspending task.
  private func waitUntilAwaiting(_ sm: BLEStateMachine) async -> Bool {
    for _ in 0..<200 {
      if await sm.isAwaitingWriteWithoutResponseReady { return true }
      try? await Task.sleep(for: .milliseconds(5))
    }
    return false
  }

  /// Reports the deadline without joining noncooperative work. The caller still owns
  /// the operation and must release and join it during cleanup when the deadline wins.
  private func completesWithin(
    _ duration: Duration,
    sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
    _ work: @escaping @Sendable () async -> Void
  ) async -> Bool {
    let race = CompletionRace()
    let observer = Task { await work(); await race.resolve(true) }
    let timer = Task {
      do { try await sleep(duration) } catch { return }
      await race.resolve(false)
    }
    let result = await race.value()
    observer.cancel()
    timer.cancel()
    return result
  }

  private actor CompletionRace {
    var result: Bool?
    var continuation: CheckedContinuation<Bool, Never>?

    func resolve(_ value: Bool) {
      guard result == nil else { return }
      result = value
      continuation?.resume(returning: value)
      continuation = nil
    }

    func value() async -> Bool {
      if let result { return result }
      return await withCheckedContinuation { continuation = $0 }
    }
  }

  private actor SuspendedWork {
    var continuation: CheckedContinuation<Void, Never>?
    var isWaiting: Bool {
      continuation != nil
    }

    func wait() async {
      await withCheckedContinuation { continuation = $0 }
    }

    func release() {
      continuation?.resume()
      continuation = nil
    }
  }

  private actor DeadlineObservation {
    var result: Bool?
    func record(_ value: Bool) {
      result = value
    }
  }

  @Test
  func `deadline reports stalled work before its owner releases and joins it`() async {
    let work = SuspendedWork()
    let observation = DeadlineObservation()
    let clock = BLETestOperationClock()
    let parkedTask = Task { await work.wait() }
    while await !work.isWaiting {
      await Task.yield()
    }
    let detector = Task {
      let result = await completesWithin(.seconds(1), sleep: clock.clock.sleep) { await parkedTask.value }
      await observation.record(result)
    }

    while clock.sleeperCount == 0 {
      await Task.yield()
    }
    clock.advance(by: .seconds(1))
    // The deadline is controlled; this separate bound only allows the observer to run.
    let observationDeadline = ContinuousClock.now.advanced(by: .seconds(1))
    while await observation.result == nil, ContinuousClock.now < observationDeadline {
      await Task.yield()
    }
    #expect(await observation.result == false)
    await work.release()
    await parkedTask.value
    await detector.value
    #expect(await observation.result == false)
  }

  // MARK: - Readiness gating

  @Test
  func `readiness wait returns immediately when peripheral is already ready`() async {
    let sm = BLEStateMachine()

    let finished = await completesWithin(.seconds(1)) {
      _ = try? await sm.awaitWriteWithoutResponseReadiness(alreadyReady: true)
    }

    #expect(finished)
    #expect(await sm.isAwaitingWriteWithoutResponseReady == false)
  }

  @Test
  func `readiness wait resumes when the peripheral signals it can write again`() async {
    let sm = BLEStateMachine()

    let waiter = Task { _ = try? await sm.awaitWriteWithoutResponseReadiness(alreadyReady: false) }
    #expect(await waitUntilAwaiting(sm))

    await sm.resumeWriteWithoutResponseReadiness()

    #expect(await completesWithin(.seconds(3)) { await waiter.value })
    #expect(await sm.isAwaitingWriteWithoutResponseReady == false)
  }

  @Test
  func `readiness wait resumes on disconnect so a sender never hangs across a drop`() async {
    let sm = BLEStateMachine()

    let waiter = Task { _ = try? await sm.awaitWriteWithoutResponseReadiness(alreadyReady: false) }
    #expect(await waitUntilAwaiting(sm))

    await sm.disconnect()

    #expect(await completesWithin(.seconds(3)) { await waiter.value })
    #expect(await sm.isAwaitingWriteWithoutResponseReady == false)
  }

  @Test
  func `readiness wait fails via the timeout backstop when the peripheral stays silent`() async {
    let sm = BLEStateMachine(writeTimeout: 0.5)

    let waiter = Task { () -> Bool in
      do {
        try await sm.awaitWriteWithoutResponseReadiness(alreadyReady: false)
        return false
      } catch {
        return true
      }
    }
    #expect(await waitUntilAwaiting(sm))

    // No ready callback and no disconnect — only the backstop timeout can release it.
    let finished = await completesWithin(.seconds(3)) { _ = await waiter.value }
    #expect(finished)
    #expect(await waiter.value)
    #expect(await sm.isAwaitingWriteWithoutResponseReady == false)
  }

  @Test
  func `peripheral-ready callback with no pending waiter is a safe no-op`() async {
    let sm = BLEStateMachine()

    await sm.resumeWriteWithoutResponseReadiness()

    #expect(await sm.isAwaitingWriteWithoutResponseReady == false)
  }

  // MARK: - Capability capture

  @Test
  func `capability is true when the write characteristic advertises writeWithoutResponse`() async {
    let sm = BLEStateMachine()
    let characteristic = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.txCharacteristic),
      properties: [.write, .writeWithoutResponse],
      value: nil,
      permissions: [.writeable]
    )

    await sm.captureWriteWithoutResponseCapability(from: characteristic)

    #expect(await sm.supportsWriteWithoutResponse == true)
  }

  @Test
  func `capability is false when the write characteristic is write-only (ESP32)`() async {
    let sm = BLEStateMachine()
    let characteristic = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.txCharacteristic),
      properties: [.write],
      value: nil,
      permissions: [.writeable]
    )

    await sm.captureWriteWithoutResponseCapability(from: characteristic)

    #expect(await sm.supportsWriteWithoutResponse == false)
  }
}
