import Foundation
import os

/// Silence is the only submitted result that means the radio powered off.
/// A reply, rejection, write error, or stopped session does not.
public enum PowerOffExchangeResult: Sendable, Equatable {
  case notSubmitted(PowerOffNotSubmitted)
  case submitted(PowerOffSubmittedResult)
}

public enum PowerOffNotSubmitted: Sendable, Equatable {
  case cancelled
  case notRunning
  case notConnected
}

public enum PowerOffSubmittedResult: Sendable, Equatable {
  /// CLI reply body. Empty means the board answered and stayed on.
  case stillOn(String)
  case rejected(UInt8)
  case sendFailed
  case silence
  case stopped
}

/// Lets cancellation win only before the power-off frame is handed to the transport.
public final class PowerOffSubmissionGate: @unchecked Sendable {
  private struct State {
    var cancelled = false
    var submitted = false
    var submissionTime: CFAbsoluteTime?
  }

  private let state = OSAllocatedUnfairLock(initialState: State())

  /// Fired on the session's executor once submission wins. Hop to the main actor before touching UI.
  public var onSubmitted: (@Sendable () -> Void)?

  public init() {}

  /// True when this call is the one that prevents the frame from being sent.
  public func cancelIfUnsubmitted() -> Bool {
    state.withLock { state in
      if state.submitted { return false }
      state.cancelled = true
      return true
    }
  }

  /// False when cancellation already won. Records the submission clock on success.
  public func beginSubmission() -> Bool {
    let started = state.withLock { state -> Bool in
      if state.cancelled || state.submitted { return false }
      state.submitted = true
      state.submissionTime = CFAbsoluteTimeGetCurrent()
      return true
    }
    if started {
      onSubmitted?()
    }
    return started
  }

  public var didSubmit: Bool {
    state.withLock(\.submitted)
  }

  public var submissionTime: CFAbsoluteTime? {
    state.withLock(\.submissionTime)
  }
}

/// Collects a power-off reply that may arrive while the write is still in flight.
private final class PowerOffReplyInbox: @unchecked Sendable {
  private struct Waiter {
    let id: UUID
    let continuation: CheckedContinuation<MeshEvent?, Never>
  }

  private struct State {
    var event: MeshEvent?
    var ended = false
    var waiters: [Waiter] = []
  }

  private let lock = OSAllocatedUnfairLock(initialState: State())

  /// Stores the first CLI reply or firmware error. Returns true when the collector should stop.
  func record(_ event: MeshEvent) -> Bool {
    let matched: MeshEvent? = switch event {
    case .cliReply, .error:
      event
    default:
      nil
    }
    guard let matched else { return false }
    let waiters = lock.withLock { state -> [Waiter] in
      state.event = matched
      let waiters = state.waiters
      state.waiters = []
      return waiters
    }
    for waiter in waiters {
      waiter.continuation.resume(returning: matched)
    }
    return true
  }

  func finish() {
    let waiters = lock.withLock { state -> [Waiter] in
      state.ended = true
      let waiters = state.waiters
      state.waiters = []
      return waiters
    }
    for waiter in waiters {
      waiter.continuation.resume(returning: nil)
    }
  }

  var streamEnded: Bool {
    lock.withLock { $0.ended && $0.event == nil }
  }

  func take(waiting seconds: TimeInterval) async -> MeshEvent? {
    let first = lock.withLock { state -> Immediate in
      if let event = state.event { return .event(event) }
      if state.ended { return .ended }
      return .waiting
    }
    switch first {
    case let .event(event):
      return event
    case .ended:
      return nil
    case .waiting:
      break
    }
    guard seconds > 0 else { return nil }

    let id = UUID()
    return await withCheckedContinuation { continuation in
      let immediate = lock.withLock { state -> Immediate in
        if let event = state.event { return .event(event) }
        if state.ended { return .ended }
        state.waiters.append(Waiter(id: id, continuation: continuation))
        return .waiting
      }
      switch immediate {
      case let .event(event):
        continuation.resume(returning: event)
      case .ended:
        continuation.resume(returning: nil)
      case .waiting:
        Task {
          try? await Task.sleep(for: .seconds(seconds))
          let removed = lock.withLock { state -> Waiter? in
            guard let index = state.waiters.firstIndex(where: { $0.id == id }) else { return nil }
            return state.waiters.remove(at: index)
          }
          removed?.continuation.resume(returning: nil)
        }
      }
    }
  }

  private enum Immediate {
    case event(MeshEvent)
    case ended
    case waiting
  }
}

public extension MeshCoreSession {
  /// Sends `poweroff` and reports whether the frame left the session.
  /// A buffered CLI reply or firmware error wins over a later write failure or a stopped session.
  func exchangePowerOff(gate: PowerOffSubmissionGate = PowerOffSubmissionGate()) async -> PowerOffExchangeResult {
    do {
      return try await requestResponseSerializer.withSerialization { [self] in
        await self.performPowerOffExchange(gate: gate)
      }
    } catch is CancellationError {
      if gate.didSubmit { return .submitted(.stopped) }
      return .notSubmitted(.cancelled)
    } catch {
      return .submitted(.sendFailed)
    }
  }

  private func performPowerOffExchange(gate: PowerOffSubmissionGate) async -> PowerOffExchangeResult {
    guard sessionIsRunning else { return .notSubmitted(.notRunning) }
    guard await transport.isConnected else { return .notSubmitted(.notConnected) }

    let inbox = PowerOffReplyInbox()
    let stream = await events()
    let collector = Task {
      for await event in stream {
        if inbox.record(event) { break }
      }
      inbox.finish()
    }
    defer { collector.cancel() }
    await Task.yield()

    guard sessionIsRunning else { return .notSubmitted(.notRunning) }
    guard gate.beginSubmission() else { return .notSubmitted(.cancelled) }

    let sendFailed: Bool
    do {
      try await transport.send(PacketBuilder.runCliCommand(PacketBuilder.powerOffCommand))
      sendFailed = false
    } catch {
      sendFailed = true
    }

    let grace = sendFailed ? Self.powerOffBufferedReplyGrace : Self.powerOffReplyWindow
    if let event = await inbox.take(waiting: grace) {
      return Self.classifyPowerOffReply(event)
    }
    if inbox.streamEnded {
      return .submitted(.stopped)
    }
    if sendFailed {
      return .submitted(.sendFailed)
    }
    return .submitted(.silence)
  }

  private static func classifyPowerOffReply(_ event: MeshEvent) -> PowerOffExchangeResult {
    switch event {
    case let .cliReply(text):
      .submitted(.stillOn(text))
    case let .error(code):
      .submitted(.rejected(code ?? 0))
    default:
      .submitted(.sendFailed)
    }
  }
}
