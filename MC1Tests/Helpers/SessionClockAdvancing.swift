import Foundation
@testable import MeshCore
import os

/// What the live test radio has seen of its last command, used to decide whether a parked session timeout can still be satisfied.
struct SessionClockObservation: Sendable {
  var channelInfosDispatched: Int
  var channelInfosYielded: Int
  var sentACommand: Bool
  var latestResponse: Data?
  var latestReplyIsHeld: Bool
}

func sessionClockObservation(
  radio: ConnectionTestRadioTransport,
  session: MeshCoreSession?
) async -> SessionClockObservation {
  let exchange = await radio.latestCommandExchange()
  let lastCommand = await radio.sentData.last?.first.flatMap(CommandCode.init(rawValue:))
  let heldReplies = if let lastCommand { await radio.heldReplyCount(for: lastCommand) } else { 0 }
  return await SessionClockObservation(
    channelInfosDispatched: session?.dispatchedChannelInfoCountForTest ?? 0,
    channelInfosYielded: radio.yieldedChannelInfoCount,
    sentACommand: exchange.sent,
    latestResponse: exchange.response,
    latestReplyIsHeld: heldReplies > 0
  )
}

/// Polls `finished`, letting each parked session timeout elapse once its reply can no longer arrive.
func advanceParkedMatchedSessionClock(
  _ clock: TestClock,
  timeout: Duration = .seconds(30),
  _ message: String = "clocked session work should finish",
  observation: @escaping @MainActor () async -> SessionClockObservation,
  until finished: @escaping @MainActor () async -> Bool
) async throws {
  try await waitUntil(timeout: timeout, message) {
    await advanceElapsedSessionTimeout(clock, observation: observation())
    return await finished()
  }
}

/// Runs `work` while advancing parked session timeouts whose replies can no longer arrive.
func resultAdvancingMatchedSessionClock<T: Sendable>(
  _ clock: TestClock,
  timeout: Duration = .seconds(30),
  observation: @escaping @MainActor () async -> SessionClockObservation,
  _ work: @escaping @MainActor @Sendable () async throws -> T
) async throws -> T {
  let slot = OSAllocatedUnfairLock<Result<T, any Error>?>(initialState: nil)
  let task = Task { @MainActor in
    let outcome: Result<T, any Error>
    do { outcome = try await .success(work()) } catch { outcome = .failure(error) }
    slot.withLock { $0 = outcome }
  }
  try await advanceParkedMatchedSessionClock(clock, timeout: timeout, observation: observation) {
    slot.withLock { $0 != nil }
  }
  task.cancel()
  guard let outcome = slot.withLock({ $0 }) else { throw CancellationError() }
  return try outcome.get()
}

private func advanceElapsedSessionTimeout(_ clock: TestClock, observation: SessionClockObservation) {
  guard clock.sleeperCount > 0, let parked = clock.nextSleepDuration else { return }
  let configuration = SessionConfiguration.default
  let grace = Duration.seconds(configuration.channelPipelinePostDrainGrace)
  if parked <= grace + SessionClockTolerance.match {
    clock.advanceToNextSleep()
    return
  }
  if durationsAreClose(parked, .seconds(configuration.channelPipelineIdleTimeout))
    || durationsAreClose(parked, .seconds(configuration.channelPipelineHardTimeout)) {
    if observation.channelInfosDispatched >= observation.channelInfosYielded {
      clock.advanceToNextSleep()
    }
    return
  }
  if durationsAreClose(parked, .seconds(configuration.contactStreamInactivityTimeout))
    || durationsAreClose(parked, .seconds(configuration.contactStreamHardTimeout)) {
    return
  }
  // A held reply is still coming once the test releases it, so its timeout must not fire early.
  guard !observation.latestReplyIsHeld else { return }
  let unmatched = observation.sentACommand
    && (observation.latestResponse == nil || observation.latestResponse?.first == ResponseCode.error.rawValue)
  if unmatched {
    clock.advanceToNextSleep()
  }
}

private enum SessionClockTolerance {
  static let match: Duration = .milliseconds(20)
  static let close: Duration = .milliseconds(50)
}

private func durationsAreClose(_ parked: Duration, _ target: Duration) -> Bool {
  let delta = parked > target ? parked - target : target - parked
  return delta <= SessionClockTolerance.close
}
