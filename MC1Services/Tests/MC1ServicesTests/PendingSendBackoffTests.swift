import Foundation
@testable import MC1Services
import SwiftData
import Testing

@MainActor
struct PendingSendBackoffTests {
  private let policy = DMBackoffPolicy(baseInterval: 120)

  @Test
  func `delay doubles every round starting at the base interval`() {
    #expect(policy.delay(forRound: 1) == 120)
    #expect(policy.delay(forRound: 2) == 240)
    #expect(policy.delay(forRound: 3) == 480)
  }

  @Test
  func `backoff fields survive the envelope, the row, and the DTO`() async throws {
    let store = try makeStore()
    let radioID = UUID()
    let envelope = DirectMessageEnvelope(messageID: UUID(), contactID: UUID(), maxBackoffRounds: 3)
    let dto = PendingSendDTO(envelope: envelope, radioID: radioID)
    _ = try await store.insertPendingSendAssigningSequence(dto)
    let stored = try #require(await store.fetchPendingSends(radioID: radioID).first)
    #expect(stored.maxBackoffRounds == 3)
    #expect(stored.backoffRound == 0)
    #expect(stored.notBefore == nil)
    #expect(stored.directMessageEnvelope()?.maxBackoffRounds == 3)
  }

  @Test
  func `an expired ACK owner becomes queued work with a delayed start`() async throws {
    let fixture = try await makeFixture(maxBackoffRounds: 3)
    let now = Date()
    let plan = try #require(await fixture.store.scheduleBackoffRetry(
      pendingSendID: fixture.row.id, expectedDeadline: fixture.deadline, policy: policy, now: now
    ))
    #expect(plan.round == 1)
    #expect(plan.maxRounds == 3)
    #expect(plan.notBefore == now.addingTimeInterval(120))

    let row = try #require(await fixture.store.fetchPendingSends(radioID: fixture.radioID).first)
    #expect(row.id == fixture.row.id)
    #expect(row.ackDeadline == nil)
    #expect(row.ackCodes == nil)
    #expect(row.attemptCount == 0)
    #expect(row.isSingleAttempt)
    #expect(row.isResend == false)
    #expect(row.backoffRound == 1)
    #expect(row.notBefore == plan.notBefore)
    // Late ACKs for the earlier timestamp stay matchable until the new send commits.
    #expect(row.nextWireAttempt == 2)

    let message = try #require(await fixture.store.fetchMessage(id: fixture.messageID))
    #expect(message.status == .retrying)
    #expect(message.retryAttempt == 0)
    #expect(message.maxRetryAttempts == 3)
    #expect(try await fixture.store.queuedDirectMessageWireAttempts().contains { $0.messageID == fixture.messageID })
  }

  /// An 8-attempt burst with 3 rounds counts to 11: the rounds show 9/11, 10/11 and 11/11.
  @Test
  func `rounds continue the burst's attempt count`() async throws {
    let fixture = try await makeFixture(maxBackoffRounds: 3)
    try await fixture.store.updateMessageRetryStatus(
      id: fixture.messageID, status: .sent, retryAttempt: 6, maxRetryAttempts: 10
    )
    var deadline = fixture.deadline
    for round in 1...3 {
      let plan = try #require(await fixture.store.scheduleBackoffRetry(
        pendingSendID: fixture.row.id, expectedDeadline: deadline, policy: policy, now: Date()
      ))
      #expect(plan.retryAttempt == 6 + round)
      #expect(plan.maxRetryAttempts == 10)
      let message = try #require(await fixture.store.fetchMessage(id: fixture.messageID))
      #expect(message.retryAttempt == 6 + round)
      #expect(message.maxRetryAttempts == 10)
      deadline = Date().addingTimeInterval(60)
      try await fixture.store.savePendingAcknowledgement(
        ackOwner(for: fixture.row, deadline: deadline), replacingPendingSendID: fixture.row.id
      )
    }
  }

  @Test
  func `each round waits twice as long and stops after the last one`() async throws {
    let fixture = try await makeFixture(maxBackoffRounds: 2)
    let now = Date()
    var deadline = fixture.deadline
    for round in 1...2 {
      let plan = try #require(await fixture.store.scheduleBackoffRetry(
        pendingSendID: fixture.row.id, expectedDeadline: deadline, policy: policy, now: now
      ))
      #expect(plan.round == round)
      #expect(plan.notBefore == now.addingTimeInterval(policy.delay(forRound: round)))
      deadline = Date().addingTimeInterval(60)
      try await fixture.store.savePendingAcknowledgement(
        ackOwner(for: fixture.row, deadline: deadline), replacingPendingSendID: fixture.row.id
      )
    }
    #expect(try await fixture.store.scheduleBackoffRetry(
      pendingSendID: fixture.row.id, expectedDeadline: deadline, policy: policy, now: now
    ) == nil)
    #expect(try await fixture.store.fetchPendingSends(radioID: fixture.radioID).first?.ackDeadline == deadline)
  }

  @Test
  func `a message that did not opt in is never scheduled`() async throws {
    let fixture = try await makeFixture(maxBackoffRounds: 0)
    #expect(try await fixture.store.scheduleBackoffRetry(
      pendingSendID: fixture.row.id, expectedDeadline: fixture.deadline, policy: policy, now: Date()
    ) == nil)
    let row = try #require(await fixture.store.fetchPendingSends(radioID: fixture.radioID).first)
    #expect(row.ackDeadline == fixture.deadline)
    #expect(row.notBefore == nil)
  }

  @Test
  func `a changed deadline or a resolved message blocks scheduling`() async throws {
    let fixture = try await makeFixture(maxBackoffRounds: 3)
    #expect(try await fixture.store.scheduleBackoffRetry(
      pendingSendID: fixture.row.id, expectedDeadline: fixture.deadline.addingTimeInterval(1),
      policy: policy, now: Date()
    ) == nil)

    try await fixture.store.updateMessageAck(id: fixture.messageID, ackCode: 9, status: .delivered, roundTripTime: nil)
    #expect(try await fixture.store.scheduleBackoffRetry(
      pendingSendID: fixture.row.id, expectedDeadline: fixture.deadline, policy: policy, now: Date()
    ) == nil)
  }

  // MARK: - Fixture

  private struct Fixture {
    let store: PersistenceStore
    let radioID: UUID
    let messageID: UUID
    let row: PendingSendDTO
    let deadline: Date
  }

  private func makeFixture(maxBackoffRounds: Int) async throws -> Fixture {
    let store = try makeStore()
    let radioID = UUID()
    let contactID = UUID()
    let messageID = UUID()
    try await store.saveMessage(makeOutgoingDM(id: messageID, radioID: radioID, contactID: contactID))
    let row = PendingSendDTO(
      envelope: DirectMessageEnvelope(messageID: messageID, contactID: contactID, maxBackoffRounds: maxBackoffRounds),
      radioID: radioID
    )
    _ = try await store.insertPendingSendAssigningSequence(row)
    #expect(try await store.setPendingSendWireAttempt(id: row.id, to: 2))
    let deadline = Date().addingTimeInterval(60)
    #expect(try await store.savePendingAcknowledgement(
      ackOwner(for: row, deadline: deadline), replacingPendingSendID: row.id
    ))
    return Fixture(store: store, radioID: radioID, messageID: messageID, row: row, deadline: deadline)
  }

  private func ackOwner(for row: PendingSendDTO, deadline: Date) -> PendingSendDTO {
    PendingSendDTO(
      id: row.id, radioID: row.radioID, messageID: row.messageID, kind: .dm,
      contactID: row.contactID, channelIndex: nil, isResend: false,
      messageText: "", messageTimestamp: 0, localNodeName: nil,
      sequence: row.sequence, enqueuedAt: row.enqueuedAt,
      ackCodes: [Data([1, 2, 3, 4])], ackSentAt: Date(), ackDeadline: deadline,
      isSingleAttempt: true
    )
  }

  private func makeOutgoingDM(id: UUID, radioID: UUID, contactID: UUID) -> MessageDTO {
    MessageDTO(
      id: id, radioID: radioID, contactID: contactID, channelIndex: nil,
      text: "backoff", timestamp: 1_700_000_000,
      createdAt: Date(timeIntervalSince1970: 1_700_000_000),
      direction: .outgoing, status: .sent, textType: .plain, ackCode: nil,
      pathLength: 0, snr: nil, senderKeyPrefix: nil, senderNodeName: nil,
      isRead: true, replyToID: nil, roundTripTime: nil, heardRepeats: 0,
      retryAttempt: 0, maxRetryAttempts: 0
    )
  }

  private func makeStore() throws -> PersistenceStore {
    PersistenceStore(modelContainer: try PersistenceStore.createContainer(inMemory: true))
  }
}
