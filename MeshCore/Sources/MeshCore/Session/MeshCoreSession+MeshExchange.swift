#if canImport(CryptoKit)
  import CryptoKit
#else
  import Crypto
#endif
import Foundation
#if canImport(os)
  import os
#endif

struct MeshExchangeSpec<Response: Sendable>: Sendable {
  let request: Data
  let publicKey: Data
  let kind: MeshExchangeKind
  let operation: String
  let acceptsBinaryResponse: Bool
  let routedEventFilter: @Sendable (MeshEvent) -> Bool
  let matchRoutedEvent: (@Sendable (MeshEvent) -> Response?)?
  let parseBinaryResponse: (@Sendable (_ payload: Data, _ tag: Data) throws -> Response?)?
  let budget: @Sendable (MessageSentInfo) -> TimeInterval
  let onTimeoutKnown: (@Sendable (TimeInterval) async -> Void)?
  let loginPasswordDigest: Data?
  let samePasswordExemption: Bool
  let onUnconsumedExit: (@Sendable (Bool) async -> Void)?
}

extension MeshCoreSession {
  /// Executes one atomic pending-slot write holding `requestResponseSerializer`
  /// only through its single host ack (`.messageSent` or `.error`).
  func sendPendingSlotWrite(
    buildFrame: @escaping @Sendable () async throws -> Data,
    preemption: PreemptionSignal? = nil,
    beforeSend: @escaping @Sendable () throws -> Void = {},
    onSubmitted: @escaping @Sendable () -> Void = {}
  ) async throws -> MessageSentInfo {
    let callerCancelled = OSAllocatedUnfairLock(initialState: false)
    return try await withTaskCancellationHandler {
      try await requestResponseSerializer.withSerialization(resumeCallerOnCancel: false) { [self] in
        if callerCancelled.withLock({ $0 }) || Task.isCancelled {
          throw CancellationError()
        }
        if preemption?.isPreempted == true {
          throw MeshCoreError.preempted(afterHandOff: false)
        }
        let frame = try await buildFrame()
        return try await sendAndMatchHoldingSerialization(
          frame,
          beforeSend: {
            if callerCancelled.withLock({ $0 }) || Task.isCancelled {
              throw CancellationError()
            }
            if preemption?.isPreempted == true {
              throw MeshCoreError.preempted(afterHandOff: false)
            }
            try beforeSend()
          },
          onSubmitted: onSubmitted
        ) { event in
          switch event {
          case let .messageSent(info):
            .success(info)
          case let .error(code):
            .failure(.deviceError(code: code ?? 0))
          default:
            .ignore
          }
        }
      }
    } onCancel: {
      callerCancelled.withLock { $0 = true }
    }
  }

  /// Acquires `MeshRequestGate` before `RequestResponseSerializer`, holding the host slot only per frame write.
  func performMeshExchange<Response: Sendable>(
    _ spec: MeshExchangeSpec<Response>,
    priority: MeshRequestPriority
  ) async throws -> Response {
    try await performMeshExchangeWithSendCount(spec, priority: priority).response
  }

  func performMeshExchangeWithSendCount<Response: Sendable>(
    _ spec: MeshExchangeSpec<Response>,
    priority: MeshRequestPriority
  ) async throws -> (response: Response, sendCount: Int) {
    let prefix = Data(spec.publicKey.prefix(MeshRequestGate.contactPrefixLength))
    let prefixHex = spec.publicKey.prefix(6).map { String(format: "%02x", $0) }.joined()
    let preemptible = priority == .background && spec.kind != .login && spec.kind != .pathDiscovery
    let preemption = preemptible ? PreemptionSignal() : nil
    let samePasswordDigest = (spec.kind == .login && spec.samePasswordExemption) ? spec.loginPasswordDigest : nil

    let onPreempt: (@Sendable () -> Void)? = preemption.map { signal in
      { @Sendable in signal.trigger() }
    }

    let ticket = try await meshRequestGate.acquire(
      priority: priority,
      contactPrefix: prefix,
      kind: spec.kind,
      preemptible: preemptible,
      samePasswordDigest: samePasswordDigest,
      onPreempt: onPreempt
    )

    let policy = MeshResendPolicy(
      kind: spec.kind,
      priority: priority,
      retransmitFloor: configuration.binaryRequestRetransmitInterval
    )
    let elapsedFromStart = clock.elapsedTimer()
    let tracker = ExchangeTracker()
    let acceptsBinaryResponse = spec.acceptsBinaryResponse
    let routedEventFilter = spec.routedEventFilter
    let (subscriptionID, events) = await dispatcher.subscribeTracked { event in
      guard tracker.hasSubmitted else { return false }
      if case .messageSent = event {
        return !tracker.hasSeenInitialMessageSent
      }
      if acceptsBinaryResponse, case .binaryResponse = event {
        return true
      }
      return routedEventFilter(event)
    }

    do {
      tracker.beginWrite()
      let firstSent: MessageSentInfo
      do {
        firstSent = try await sendPendingSlotWrite(
          buildFrame: { spec.request },
          preemption: preemption,
          onSubmitted: { tracker.markSubmitted() }
        )
      } catch {
        tracker.endWrite()
        if case .deviceError = error as? MeshCoreError {
          // Companion refused the command synchronously before arming its pending slot.
          tracker.clearSubmittedOnRefusal()
        }
        throw error
      }
      tracker.markInitialMessageSent()
      tracker.recordSent(firstSent, atOffset: elapsedFromStart())

      let tagHex = firstSent.expectedAck.map { String(format: "%02x", $0) }.joined()
      logger.info(
        "\(spec.operation) request to \(prefixHex): messageSent #1 tag=\(tagHex) route=\(firstSent.route) suggestedTimeoutMs=\(firstSent.suggestedTimeoutMs)"
      )

      if preemption?.isPreempted == true {
        gateLogger.info("\(spec.operation) to \(prefixHex): preempted (afterHandOff=true)")
        throw MeshCoreError.preempted(afterHandOff: true)
      }
      try Task.checkCancellation()

      let resolvedBudget = spec.budget(firstSent)
      await spec.onTimeoutKnown?(resolvedBudget)

      if preemption?.isPreempted == true {
        gateLogger.info("\(spec.operation) to \(prefixHex): preempted (afterHandOff=true)")
        throw MeshCoreError.preempted(afterHandOff: true)
      }
      try Task.checkCancellation()

      let response = try await waitForMeshExchangeReply(
        spec: spec,
        events: events,
        tracker: tracker,
        policy: policy,
        preemption: preemption,
        elapsedFromStart: elapsedFromStart,
        budget: resolvedBudget,
        prefixHex: prefixHex
      )
      await dispatcher.finishSubscription(id: subscriptionID)
      let releaseHold = makeSameContactHold(
        tracker: tracker,
        policy: policy,
        succeeded: true,
        loginPasswordDigest: spec.kind == .login ? spec.loginPasswordDigest : nil
      )
      await meshRequestGate.release(ticket, sameContactHold: releaseHold)
      return (response, tracker.snapshot().sendCount)
    } catch {
      await dispatcher.finishSubscription(id: subscriptionID)
      if let meshError = error as? MeshCoreError, case let .preempted(afterHandOff) = meshError {
        gateLogger.info("\(spec.operation) to \(prefixHex): preempted (afterHandOff=\(afterHandOff))")
      }
      let replyMayBeInFlight = tracker.snapshot().replyMayBeInFlight
      if sessionIsRunning {
        await spec.onUnconsumedExit?(replyMayBeInFlight)
      }
      let releaseHold = makeSameContactHold(
        tracker: tracker,
        policy: policy,
        succeeded: false,
        loginPasswordDigest: spec.kind == .login ? spec.loginPasswordDigest : nil
      )
      await meshRequestGate.release(ticket, sameContactHold: releaseHold)
      throw error
    }
  }

  private func makeSameContactHold(
    tracker: ExchangeTracker,
    policy: MeshResendPolicy,
    succeeded: Bool,
    loginPasswordDigest: Data?
  ) -> SameContactHoldRequest? {
    let snapshot = tracker.snapshot()
    guard sessionIsRunning, snapshot.replyMayBeInFlight, !succeeded || snapshot.sendCount > 1 else {
      return nil
    }
    let duration = Duration.seconds(policy.spacing(suggestedTimeoutMs: snapshot.lastSuggestedTimeoutMs))
    return SameContactHoldRequest(duration: duration, loginPasswordDigest: loginPasswordDigest)
  }

  private func waitForMeshExchangeReply<Response: Sendable>(
    spec: MeshExchangeSpec<Response>,
    events: AsyncStream<MeshEvent>,
    tracker: ExchangeTracker,
    policy: MeshResendPolicy,
    preemption: PreemptionSignal?,
    elapsedFromStart: @escaping @Sendable () -> Duration,
    budget: TimeInterval,
    prefixHex: String
  ) async throws -> Response {
    let startOffset = tracker.snapshot().lastWriteOffset ?? elapsedFromStart()
    let overallDeadlineOffset = startOffset + .seconds(max(0, budget))

    return try await withThrowingTaskGroup(of: MeshExchangeSignal<Response>.self) { group in
      group.addTask { [self] in
        try await listenForMeshExchangeEvent(
          spec: spec,
          events: events,
          tracker: tracker,
          prefixHex: prefixHex
        )
      }

      if let preemption {
        group.addTask {
          await preemption.wait()
          return Task.isCancelled ? .idle : .preempted
        }
      }

      group.addTask { [self] in
        try await runMeshExchangeTimerAndResends(
          spec: spec,
          tracker: tracker,
          policy: policy,
          preemption: preemption,
          elapsedFromStart: elapsedFromStart,
          startOffset: startOffset,
          overallDeadlineOffset: overallDeadlineOffset,
          prefixHex: prefixHex
        )
      }

      do {
        while let signal = try await group.next() {
          switch signal {
          case let .response(response):
            group.cancelAll()
            while await (try? group.next()) != nil {}
            return response
          case .timedOut:
            group.cancelAll()
            while await (try? group.next()) != nil {}
            throw MeshCoreError.timeout
          case .stopped:
            group.cancelAll()
            while await (try? group.next()) != nil {}
            throw CancellationError()
          case .preempted:
            group.cancelAll()
            while await (try? group.next()) != nil {}
            throw MeshCoreError.preempted(afterHandOff: tracker.snapshot().replyMayBeInFlight)
          case .idle:
            continue
          }
        }
      } catch {
        group.cancelAll()
        while await (try? group.next()) != nil {}
        throw error
      }
      try Task.checkCancellation()
      if !sessionIsRunning {
        throw CancellationError()
      }
      throw MeshCoreError.timeout
    }
  }

  private func listenForMeshExchangeEvent<Response: Sendable>(
    spec: MeshExchangeSpec<Response>,
    events: AsyncStream<MeshEvent>,
    tracker: ExchangeTracker,
    prefixHex: String
  ) async throws -> MeshExchangeSignal<Response> {
    var seenInitialMessageSent = false
    for await event in events {
      if Task.isCancelled { return .idle }
      if case .messageSent = event {
        seenInitialMessageSent = true
        continue
      }
      guard seenInitialMessageSent else { continue }
      if case let .binaryResponse(tag, responseData) = event, spec.acceptsBinaryResponse {
        if !tracker.containsTag(tag) {
          await tracker.waitUntilWriteSettled()
        }
        guard tracker.containsTag(tag) else {
          let holdActive = await meshRequestGate.hasAnyActiveHold
          gateLogger.debug(
            "Unaccepted binaryResponse ignored during \(spec.operation) to \(prefixHex) (holdActive=\(holdActive))"
          )
          continue
        }
        guard let response = try spec.parseBinaryResponse?(responseData, tag) else {
          let preview = responseData.prefix(32).hexString
          logger.warning(
            "\(spec.operation) request to \(prefixHex): binary response parse failed (\(responseData.count) bytes, prefix=\(preview))"
          )
          throw MeshCoreError.parseError(
            "\(spec.operation) binary response unparseable (\(responseData.count) bytes)"
          )
        }
        return .response(response)
      }

      if let routed = spec.matchRoutedEvent?(event) {
        return .response(routed)
      }

      switch event {
      case .binaryResponse, .loginSuccess, .loginFailed, .statusResponse:
        let holdActive = await meshRequestGate.hasAnyActiveHold
        gateLogger.debug(
          "Unaccepted \(event.caseName) ignored during \(spec.operation) to \(prefixHex) (holdActive=\(holdActive))"
        )
      default:
        break
      }
    }
    return sessionIsRunning ? .idle : .stopped
  }

  private func runMeshExchangeTimerAndResends<Response: Sendable>(
    spec: MeshExchangeSpec<Response>,
    tracker: ExchangeTracker,
    policy: MeshResendPolicy,
    preemption: PreemptionSignal?,
    elapsedFromStart: @escaping @Sendable () -> Duration,
    startOffset: Duration,
    overallDeadlineOffset: Duration,
    prefixHex: String
  ) async throws -> MeshExchangeSignal<Response> {
    while !Task.isCancelled {
      let snapshot = tracker.snapshot()
      let maxSends = policy.maxSends(route: snapshot.route)
      let spacing = policy.spacing(suggestedTimeoutMs: snapshot.lastSuggestedTimeoutMs)
      let lastWrite = snapshot.lastWriteOffset ?? startOffset

      if snapshot.sendCount >= maxSends {
        let effectiveDeadline: Duration = if policy.shouldTrimAfterFinalSend(
          route: snapshot.route,
          suggestedTimeoutMs: snapshot.lastSuggestedTimeoutMs
        ) {
          min(overallDeadlineOffset, lastWrite + .seconds(spacing))
        } else {
          overallDeadlineOffset
        }
        let remaining = effectiveDeadline - elapsedFromStart()
        if remaining > .zero {
          do {
            try await clock.sleep(for: remaining)
          } catch is CancellationError {
            return .idle
          }
        }
        guard !Task.isCancelled else { return .idle }
        return sessionIsRunning ? .timedOut : .stopped
      }

      let nextResend = lastWrite + .seconds(spacing)
      let sleepTarget = min(overallDeadlineOffset, nextResend)
      let sleepDuration = sleepTarget - elapsedFromStart()
      if sleepDuration > .zero {
        do {
          try await clock.sleep(for: sleepDuration)
        } catch is CancellationError {
          return .idle
        }
      }
      guard !Task.isCancelled else { return .idle }
      guard sessionIsRunning else { return .stopped }
      if preemption?.isPreempted == true { return .preempted }
      if elapsedFromStart() >= overallDeadlineOffset {
        return .timedOut
      }

      let attempt = snapshot.sendCount + 1
      logger.info(
        "\(spec.operation) request to \(prefixHex): retransmit #\(attempt) (spacing=\(String(format: "%.1f", spacing))s)"
      )
      tracker.beginWrite()
      do {
        let sent = try await sendPendingSlotWrite(
          buildFrame: { spec.request },
          preemption: preemption,
          onSubmitted: { tracker.markSubmitted() }
        )
        tracker.recordSent(sent, atOffset: elapsedFromStart())
        let tagHex = sent.expectedAck.map { String(format: "%02x", $0) }.joined()
        logger.info(
          "\(spec.operation) request to \(prefixHex): messageSent #\(attempt) tag=\(tagHex) suggestedTimeoutMs=\(sent.suggestedTimeoutMs)"
        )
      } catch is CancellationError {
        tracker.endWrite()
        return .idle
      } catch let meshError as MeshCoreError where meshError.isPreemptedError {
        tracker.endWrite()
        return .preempted
      } catch {
        tracker.endWrite()
        tracker.recordResendAttemptFailed(atOffset: elapsedFromStart())
        logger.warning(
          "\(spec.operation) request to \(prefixHex): retransmit #\(attempt) failed (\(error.localizedDescription)); continuing"
        )
      }
    }
    return .idle
  }

  static func sha256Digest(of password: String) -> Data {
    Data(SHA256.hash(data: Data(password.utf8)))
  }

  func recordUnconsumedLoginDigest(_ digest: Data, forPrefix prefix: Data) {
    guard sessionIsRunning else { return }
    unconsumedLoginDigests[prefix, default: []].insert(digest)
  }

  func consumeLoginDigests(forPrefix prefix: Data, currentDigest: Data) -> Bool {
    guard let unconsumed = unconsumedLoginDigests.removeValue(forKey: prefix) else {
      return false
    }
    let mayAnswerEarlier = unconsumed.contains { $0 != currentDigest }
    if mayAnswerEarlier {
      gateLogger.info("Login reply for \(prefix.hexString) may answer an earlier unconsumed login")
    }
    return mayAnswerEarlier
  }
}

private extension Clock where Duration == Swift.Duration {
  func elapsedTimer() -> @Sendable () -> Duration {
    let start = now
    return { start.duration(to: self.now) }
  }
}

private extension MeshCoreError {
  var isPreemptedError: Bool {
    if case .preempted = self { return true }
    return false
  }
}

final class PreemptionSignal: Sendable {
  private struct State {
    var isPreempted = false
    var waiter: CheckedContinuation<Void, Never>?
  }

  private let state = OSAllocatedUnfairLock(initialState: State())

  var isPreempted: Bool {
    state.withLock { $0.isPreempted }
  }

  func trigger() {
    let waiter = state.withLock { state -> CheckedContinuation<Void, Never>? in
      state.isPreempted = true
      let current = state.waiter
      state.waiter = nil
      return current
    }
    waiter?.resume()
  }

  func wait() async {
    await withTaskCancellationHandler {
      await withCheckedContinuation { continuation in
        let shouldResumeImmediately = state.withLock { state -> Bool in
          if state.isPreempted || Task.isCancelled {
            return true
          }
          state.waiter = continuation
          return false
        }
        if shouldResumeImmediately {
          continuation.resume()
        }
      }
    } onCancel: {
      let waiter = state.withLock { state -> CheckedContinuation<Void, Never>? in
        let current = state.waiter
        state.waiter = nil
        return current
      }
      waiter?.resume()
    }
  }
}

private final class ExchangeTracker: Sendable {
  struct Snapshot: Sendable {
    var sentTags: Set<Data> = []
    var sendCount = 0
    var submitted = false
    var replyMayBeInFlight = false
    var seenInitialMessageSent = false
    var lastSuggestedTimeoutMs: UInt32 = 0
    var route: UInt8 = 0
    var lastWriteOffset: Duration?
    var writeInFlight = false
    var writeSettledWaiters: [CheckedContinuation<Void, Never>] = []
  }

  private let state = OSAllocatedUnfairLock(initialState: Snapshot())

  var hasSubmitted: Bool {
    state.withLock { $0.submitted }
  }

  var hasSeenInitialMessageSent: Bool {
    state.withLock { $0.seenInitialMessageSent }
  }

  func markInitialMessageSent() {
    state.withLock { $0.seenInitialMessageSent = true }
  }

  func beginWrite() {
    state.withLock { $0.writeInFlight = true }
  }

  func endWrite() {
    let waiters = state.withLock { snapshot -> [CheckedContinuation<Void, Never>] in
      snapshot.writeInFlight = false
      let pending = snapshot.writeSettledWaiters
      snapshot.writeSettledWaiters.removeAll()
      return pending
    }
    for waiter in waiters {
      waiter.resume()
    }
  }

  func markSubmitted() {
    state.withLock { snapshot in
      snapshot.submitted = true
      snapshot.replyMayBeInFlight = true
    }
  }

  func clearSubmittedOnRefusal() {
    state.withLock { snapshot in
      if snapshot.sendCount == 0 {
        snapshot.replyMayBeInFlight = false
      }
    }
  }

  func recordSent(_ info: MessageSentInfo, atOffset offset: Duration) {
    let waiters = state.withLock { snapshot -> [CheckedContinuation<Void, Never>] in
      snapshot.sentTags.insert(info.expectedAck)
      snapshot.sendCount += 1
      snapshot.replyMayBeInFlight = true
      snapshot.lastSuggestedTimeoutMs = info.suggestedTimeoutMs
      snapshot.route = info.route
      snapshot.lastWriteOffset = offset
      snapshot.writeInFlight = false
      let pending = snapshot.writeSettledWaiters
      snapshot.writeSettledWaiters.removeAll()
      return pending
    }
    for waiter in waiters {
      waiter.resume()
    }
  }

  func recordResendAttemptFailed(atOffset offset: Duration) {
    state.withLock { snapshot in
      snapshot.sendCount += 1
      snapshot.lastWriteOffset = offset
    }
  }

  func containsTag(_ tag: Data) -> Bool {
    state.withLock { $0.sentTags.contains(tag) }
  }

  func snapshot() -> Snapshot {
    state.withLock { $0 }
  }

  func waitUntilWriteSettled() async {
    await withCheckedContinuation { continuation in
      let settled = state.withLock { snapshot -> Bool in
        if !snapshot.writeInFlight {
          return true
        }
        snapshot.writeSettledWaiters.append(continuation)
        return false
      }
      if settled {
        continuation.resume()
      }
    }
  }
}

private enum MeshExchangeSignal<Response: Sendable>: Sendable {
  case response(Response)
  case timedOut
  case stopped
  case preempted
  case idle
}
