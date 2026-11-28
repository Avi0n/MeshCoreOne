import Foundation

// MARK: - Backoff Wake-Up

extension ChatSendQueueService {
  /// Arms one timer for the earliest backoff round. A later `notBefore` leaves an earlier timer in place.
  func armBackoffWake(at notBefore: Date) {
    guard !isRetired else { return }
    if let armed = backoffWakeAt, armed <= notBefore, backoffWakeTask != nil { return }
    backoffWakeTask?.cancel()
    backoffWakeAt = notBefore
    let wait = max(0, notBefore.timeIntervalSinceNow)
    backoffWakeTask = Task { [weak self] in
      try? await Task.sleep(for: .seconds(wait))
      guard !Task.isCancelled else { return }
      await self?.enqueueDueBackoffRounds()
    }
  }

  func cancelBackoffWake() {
    backoffWakeTask?.cancel()
    backoffWakeTask = nil
    backoffWakeAt = nil
  }

  /// Hands every round whose `notBefore` has passed to the DM drain, then re-arms for the next one.
  func enqueueDueBackoffRounds() async {
    backoffWakeTask = nil
    backoffWakeAt = nil
    guard beginAdmission() else { return }
    defer { finishAdmission() }
    do {
      let now = Date()
      var nextWake: Date?
      for dto in try await dataStore.fetchPendingSends(radioID: radioID) {
        guard dto.kind == .dm, dto.ackDeadline == nil, let notBefore = dto.notBefore else { continue }
        if notBefore > now {
          nextWake = min(nextWake ?? notBefore, notBefore)
          continue
        }
        guard backoffEnqueuedRound[dto.messageID] != dto.backoffRound,
              let envelope = dto.directMessageEnvelope() else { continue }
        backoffEnqueuedRound[dto.messageID] = dto.backoffRound
        await dmQueueEnqueue(envelope)
      }
      if let nextWake { armBackoffWake(at: nextWake) }
    } catch {
      logger.error("Backoff wake failed: \(String(describing: error))")
    }
  }
}
