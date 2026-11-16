import Foundation

/// In-process WiFi mutation and apply evidence for one Radio ID.
@MainActor
public final class RadioWiFiState {
  public nonisolated let radioID: UUID
  public private(set) var revision: UInt64 = 0
  public private(set) var rebootPending = false
  private var mutations: Set<UUID> = []
  private var expectedReboot: ExpectedReboot?
  private var admissionClosed = false
  private var settlementWaiters: [CheckedContinuation<Void, Never>] = []

  public nonisolated init(radioID: UUID) {
    self.radioID = radioID
  }

  public struct MutationToken: Sendable {
    let id: UUID
    let revision: UInt64
  }

  public enum MutationOutcome: Sendable {
    case accepted
    case rejected
    case uncertain
  }

  private struct ExpectedReboot {
    let id: UUID
    let contextID: UUID
    let revision: UInt64
    let observation: RadioWiFiResetObservation?
    var submitted = false
    var linkEnded = false
  }

  public var expectedRebootID: UUID? {
    expectedReboot?.id
  }

  public func stableRevision() -> UInt64? {
    mutations.isEmpty && !admissionClosed ? revision : nil
  }

  public func beginMutation(expectedRevision: UInt64?) -> MutationToken? {
    guard !admissionClosed else { return nil }
    if let expectedRevision {
      guard mutations.isEmpty, revision == expectedRevision else { return nil }
    }
    expectedReboot = nil
    revision &+= 1
    let token = MutationToken(id: UUID(), revision: revision)
    mutations.insert(token.id)
    return token
  }

  public func isUncontended(_ token: MutationToken) -> Bool {
    !admissionClosed && mutations.count == 1 && mutations.contains(token.id) && revision == token.revision
  }

  @discardableResult
  public func settle(_ token: MutationToken, outcome: MutationOutcome) -> UInt64? {
    let uncontended = isUncontended(token)
    guard mutations.remove(token.id) != nil else { return nil }
    revision &+= 1
    switch outcome {
    case .accepted, .uncertain:
      rebootPending = true
    case .rejected:
      break
    }
    if mutations.isEmpty {
      let waiters = settlementWaiters
      settlementWaiters.removeAll()
      for waiter in waiters {
        waiter.resume()
      }
    }
    return uncontended && mutations.isEmpty ? revision : nil
  }

  func closeAdmission() {
    admissionClosed = true
    expectedReboot = nil
  }

  func openAdmission() {
    admissionClosed = false
  }

  func waitForMutationSettlement() async {
    guard !mutations.isEmpty else { return }
    await withCheckedContinuation { settlementWaiters.append($0) }
  }

  func adoptCanonicalEvidence(rebootPending inherited: Bool) {
    rebootPending = rebootPending || inherited
    revision &+= 1
    expectedReboot = nil
  }

  public func prepareExpectedReboot(
    contextID: UUID, expectedRevision: UInt64? = nil, observation: RadioWiFiResetObservation? = nil
  ) -> UUID? {
    if let expectedRevision, expectedRevision != revision { return nil }
    guard !admissionClosed, rebootPending, mutations.isEmpty else { return nil }
    let id = UUID()
    expectedReboot = ExpectedReboot(id: id, contextID: contextID, revision: revision, observation: observation)
    return id
  }

  public func completeRebootSubmission(id: UUID, succeeded: Bool) {
    guard expectedReboot?.id == id else { return }
    if succeeded {
      expectedReboot?.submitted = true
    } else {
      expectedReboot = nil
    }
  }

  public func noteLinkEnded(contextID: UUID) {
    guard expectedReboot?.contextID == contextID else { return }
    expectedReboot?.linkEnded = true
  }

  public func cancelExpectedReboot() {
    expectedReboot = nil
  }

  @discardableResult
  public func clearExpectedReboot(
    id: UUID, contextID: UUID, readRevision: UInt64, observation: RadioWiFiResetObservation? = nil
  ) -> Bool {
    guard let candidate = expectedReboot,
          candidate.id == id,
          candidate.contextID != contextID,
          candidate.submitted,
          candidate.linkEnded,
          !admissionClosed,
          let before = candidate.observation, let observation, observation.provesReset(since: before),
          mutations.isEmpty,
          candidate.revision == revision,
          readRevision == revision else { return false }
    rebootPending = false
    expectedReboot = nil
    return true
  }
}
