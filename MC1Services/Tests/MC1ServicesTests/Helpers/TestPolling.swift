import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import os

/// Polls a condition at short intervals until it returns `true` or a timeout expires.
/// Replaces fixed `Task.sleep` calls in tests where we wait for an asynchronous state change.
///
/// - Parameters:
///   - timeout: Maximum time to wait before failing.
///   - pollingInterval: How often to re-check the condition.
///   - message: Failure message if the timeout expires.
///   - condition: An async closure returning `true` when the expected state is reached.
func waitUntil(
  timeout: Duration = .seconds(10),
  pollingInterval: Duration = .milliseconds(10),
  _ message: String = "waitUntil timed out",
  condition: @escaping @MainActor () async throws -> Bool
) async throws {
  let deadline = ContinuousClock.now + timeout
  while ContinuousClock.now < deadline {
    if try await condition() { return }
    try await Task.sleep(for: pollingInterval)
  }
  if try await condition() { return }
  struct WaitTimeoutError: Error, CustomStringConvertible {
    let description: String
  }
  throw WaitTimeoutError(description: message)
}

struct SessionClockObservation: Sendable {
  var channelInfosDispatched: Int
  var channelInfosYielded: Int
  var sentACommand: Bool
  var latestResponse: Data?
}

func sessionClockObservation(
  radio: ConnectionTestRadioTransport,
  session: MeshCoreSession?
) async -> SessionClockObservation {
  let exchange = await radio.latestCommandExchange()
  return await SessionClockObservation(
    channelInfosDispatched: session?.dispatchedChannelInfoCountForTest ?? 0,
    channelInfosYielded: radio.yieldedChannelInfoCount,
    sentACommand: exchange.sent,
    latestResponse: exchange.response
  )
}

/// Polls like `waitUntil`, and lets each production timeout elapse on `clock` as soon as it parks.
func waitUntilClockAdvances(
  _ clock: TestClock,
  timeout: Duration = .seconds(5),
  _ message: String = "waitUntil timed out",
  _ condition: @escaping @MainActor () async throws -> Bool
) async throws {
  try await waitUntil(timeout: timeout, message) {
    if clock.sleeperCount > 0 { clock.advanceToNextSleep() }
    return try await condition()
  }
}

/// Lets a parked production timeout elapse when its reply can no longer succeed.
/// The channel pipeline's idle sleep waits until every yielded channelInfo has been dispatched.
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

func resultAdvancingMatchedSessionClock<T: Sendable>(
  _ clock: TestClock,
  timeout: Duration = .seconds(30),
  observation: @escaping @MainActor () async -> SessionClockObservation,
  _ work: @escaping @MainActor @Sendable () async throws -> T
) async throws -> T {
  let slot = OSAllocatedUnfairLock<Result<T, Error>?>(initialState: nil)
  let task = Task { @MainActor in
    let outcome: Result<T, Error>
    do { outcome = try await .success(work()) } catch { outcome = .failure(error) }
    slot.withLock { $0 = outcome }
  }
  try await advanceParkedMatchedSessionClock(clock, timeout: timeout, observation: observation) {
    slot.withLock { $0 != nil }
  }
  task.cancel()
  return try slot.withLock { try $0!.get() }
}

private func advanceElapsedSessionTimeout(_ clock: TestClock, observation: SessionClockObservation) {
  guard clock.sleeperCount > 0, let parked = clock.nextSleepDuration else { return }
  let configuration = SessionConfiguration.default
  let grace = Duration.seconds(configuration.channelPipelinePostDrainGrace)
  if parked <= grace + .milliseconds(20) {
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
  let unmatched = observation.sentACommand
    && (observation.latestResponse == nil || observation.latestResponse?.first == ResponseCode.error.rawValue)
  if unmatched {
    clock.advanceToNextSleep()
  }
}

func advancePowerOffWindowIfParked(_ clock: TestClock) {
  guard clock.sleeperCount > 0, let parked = clock.nextSleepDuration else { return }
  let grace = Duration.seconds(MeshCoreSession.powerOffBufferedReplyGrace)
  let window = Duration.seconds(MeshCoreSession.powerOffReplyWindow)
  if parked <= grace + .milliseconds(20) || durationsAreClose(parked, window) {
    clock.advanceToNextSleep()
  }
}

private func durationsAreClose(_ parked: Duration, _ target: Duration, tolerance: Duration = .milliseconds(50)) -> Bool {
  let delta = parked > target ? parked - target : target - parked
  return delta <= tolerance
}
