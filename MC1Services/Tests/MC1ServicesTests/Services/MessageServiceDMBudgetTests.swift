import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import os
import Testing

@Suite("DM send budget")
@MainActor
struct MessageServiceDMBudgetTests {
  private let senderKey = Data(repeating: 0xAB, count: 32)
  private let preservedTimestamp: UInt32 = 1_700_000_000
  /// A device rejection other than the direct-message table-full code.
  private let rejectedDirectMessageCode: UInt8 = 1

  @Test(arguments: DMSendBudget.directRange, DMSendBudget.floodRange)
  func `each budget sends its attempts and resets only before fallback`(
    directAttempts: Int,
    floodAttempts: Int
  ) async throws {
    let observed = try await exhaust(budget: DMSendBudget(directAttempts: directAttempts, floodAttempts: floodAttempts))
    let totalAttempts = directAttempts + floodAttempts
    #expect(observed.attempts == Array(0..<UInt8(totalAttempts)))
    #expect(observed.resetIndexes == (floodAttempts > 0 ? [directAttempts] : []))
    #expect(observed.retryMaxAttempts == Array(repeating: totalAttempts - 1, count: totalAttempts - 1))
  }

  @Test
  func `a rejected path reset still uses the remaining attempts without another reset`() async throws {
    let observed = try await exhaust(
      budget: DMSendBudget(directAttempts: 2, floodAttempts: 2),
      resetPathFails: true
    )
    #expect(observed.attempts == [0, 1, 2, 3])
    #expect(observed.resetIndexes == [2])
  }

  @Test
  func `a resumed drain resets the path before the first byte at the flood boundary`() async throws {
    let observed = try await drainPreserved(
      startingAttempt: 2,
      budget: DMSendBudget(directAttempts: 2, floodAttempts: 2)
    )
    #expect(observed.attempts == [2, 3, 4, 5])
    #expect(observed.resetIndexes == [0])
  }

  @Test
  func `a resumed drain below the flood boundary sends before resetting`() async throws {
    let observed = try await drainPreserved(
      startingAttempt: 1,
      budget: DMSendBudget(directAttempts: 2, floodAttempts: 2)
    )
    #expect(observed.attempts == [1, 2, 3, 4])
    #expect(observed.resetIndexes == [1])
  }

  @Test
  func `a single-attempt replay submits only the next byte`() async throws {
    let observed = try await drainPreserved(startingAttempt: 1, budget: .singleAttempt)
    #expect(observed.attempts == [1])
    #expect(observed.resetIndexes.isEmpty)
    #expect(observed.messageIDs.count == 1)
  }

  @Test
  func `an exhausted wire timestamp stays matchable and sends no new command`() async throws {
    let harness = try await makePreservedHarness(startingAttempt: AckCodeBuilder.attemptCount, budget: .singleAttempt)
    defer { Task { await harness.session.stop() } }
    let before = await harness.transport.sentData.count
    let returned = try await harness.service.performQueuedDirectMessageSend(
      messageID: harness.messageID,
      to: harness.contact,
      preserveTimestamp: true,
      isResend: false,
      isSingleAttempt: false,
      pendingSendID: harness.pendingID,
      sendLease: nil
    )
    let commands = await sendCommands(on: harness.transport, after: before)
    #expect(commands.isEmpty)
    #expect(returned.status == .pending)
    let stored = try #require(try await harness.store.fetchMessage(id: harness.messageID))
    #expect(stored.status == .pending)
    #expect(stored.timestamp == preservedTimestamp)
    #expect(try await harness.store.fetchMessages(contactID: harness.contact.id).count == 1)
    let owner = try await pendingRow(in: harness)
    #expect(owner.ackDeadline != nil)
    #expect(owner.ackCodes?.isEmpty == false)
    #expect(owner.nextWireAttempt == AckCodeBuilder.attemptCount)
    #expect(try await harness.store.pendingSendWireAttempt(id: harness.pendingID) == nil)

    let code = AckCodeBuilder.expectedAck(
      timestamp: stored.timestamp,
      attempt: 0,
      text: stored.text,
      senderPublicKey: senderKey
    )
    await harness.service.handleAcknowledgement(code: code, tripTime: 40)
    let delivered = try #require(try await harness.store.fetchMessage(id: harness.messageID))
    #expect(delivered.status == .delivered)
    #expect(try await harness.store.hasPendingSend(messageID: harness.messageID) == false)
    let afterAck = await sendCommands(on: harness.transport, after: before)
    #expect(afterAck.isEmpty)
  }

  @Test
  func `an exhausted failed row becomes pending with an acknowledgement deadline`() async throws {
    let harness = try await makePreservedHarness(
      startingAttempt: AckCodeBuilder.attemptCount,
      budget: .singleAttempt,
      config: MessageServiceConfig(minTimeout: 0, ackGiveUpWindow: 0)
    )
    defer { Task { await harness.session.stop() } }
    try await harness.store.updateMessageStatus(id: harness.messageID, status: .failed)
    let before = await harness.transport.sentData.count
    let returned = try await harness.service.performQueuedDirectMessageSend(
      messageID: harness.messageID,
      to: harness.contact,
      preserveTimestamp: true,
      isResend: false,
      isSingleAttempt: false,
      pendingSendID: harness.pendingID,
      sendLease: nil
    )
    #expect(returned.status == .pending)
    let commands = await sendCommands(on: harness.transport, after: before)
    #expect(commands.isEmpty)
    let stored = try #require(try await harness.store.fetchMessage(id: harness.messageID))
    #expect(stored.status == .pending)
    #expect(stored.timestamp == preservedTimestamp)
    let owner = try await pendingRow(in: harness)
    #expect(owner.ackDeadline != nil)
    try await Task.sleep(for: .milliseconds(20))
    try await harness.service.checkExpiredAcks()
    let failed = try #require(try await harness.store.fetchMessage(id: harness.messageID))
    #expect(failed.status == .failed)
    #expect(failed.timestamp == preservedTimestamp)
    #expect(try await harness.store.hasPendingSend(messageID: harness.messageID) == false)
  }

  @Test
  func `an accepted command still stores sent when the attempt byte is exhausted`() async throws {
    let harness = try await makePreservedHarness(startingAttempt: AckCodeBuilder.attemptCount - 1, budget: .singleAttempt)
    defer { Task { await harness.session.stop() } }
    let responder = Task { await answerCommands(on: harness.transport, after: 1, resetPathFails: false) }
    defer { responder.cancel() }
    let returned = try await harness.service.performQueuedDirectMessageSend(
      messageID: harness.messageID,
      to: harness.contact,
      preserveTimestamp: true,
      isResend: false,
      isSingleAttempt: false,
      pendingSendID: harness.pendingID,
      sendLease: nil
    )
    responder.cancel()
    #expect(returned.status == .sent)
    let stored = try #require(try await harness.store.fetchMessage(id: harness.messageID))
    #expect(stored.status == .sent)
    #expect(stored.timestamp == preservedTimestamp)
  }

  @Test
  func `a rejected resend of a delivered message keeps the delivery and the old timestamp`() async throws {
    let harness = try await makePreservedHarness(startingAttempt: 0, budget: .singleAttempt)
    defer { Task { await harness.session.stop() } }
    try await harness.store.updateMessageStatus(id: harness.messageID, status: .delivered)
    let events = harness.service.statusEvents()
    let responder = Task {
      await answerCommands(on: harness.transport, after: 1, resetPathFails: false, rejectSend: true)
    }
    defer { responder.cancel() }
    let returned = try await harness.service.performQueuedDirectMessageSend(
      messageID: harness.messageID,
      to: harness.contact,
      preserveTimestamp: false,
      isResend: true,
      isSingleAttempt: false,
      pendingSendID: harness.pendingID,
      sendLease: nil
    )
    responder.cancel()
    #expect(returned.status == .delivered)
    #expect(returned.timestamp == preservedTimestamp)
    let stored = try #require(try await harness.store.fetchMessage(id: harness.messageID))
    #expect(stored.status == .delivered)
    #expect(stored.timestamp == preservedTimestamp)
    #expect(try await harness.store.hasPendingSend(messageID: harness.messageID) == false)
    #expect(await deliveredResolutions(on: harness.service, from: events) == [harness.messageID])
  }

  @Test
  func `a table full resend keeps the new timestamp and resubmits attempt zero`() async throws {
    let harness = try await makePreservedHarness(
      startingAttempt: 0,
      budget: .singleAttempt,
      config: MessageServiceConfig(minTimeout: 0, poolBackoff: PoolBackoffConfig(attemptCap: 0, baseDelay: 0))
    )
    defer { Task { await harness.session.stop() } }
    try await harness.store.updateMessageStatus(id: harness.messageID, status: .delivered)
    let bumped = try #require(try await harness.store.preparePendingSendAttempt(messageID: harness.messageID))
    #expect(bumped.attemptCount == 1)
    let events = harness.service.statusEvents()
    let responder = Task {
      await answerCommands(on: harness.transport, after: 1, resetPathFails: false, tableFull: true)
    }
    defer { responder.cancel() }
    let error = await #expect(throws: MeshCoreError.self) {
      try await harness.service.performQueuedDirectMessageSend(
        messageID: harness.messageID,
        to: harness.contact,
        preserveTimestamp: false,
        isResend: true,
        isSingleAttempt: false,
        pendingSendID: harness.pendingID,
        sendLease: nil
      )
    }
    responder.cancel()
    guard case let .deviceError(code)? = error else {
      Issue.record("Expected table full, got \(String(describing: error))")
      return
    }
    #expect(code == FirmwareDeviceErrorCode.directMessageTableFull)
    let stored = try #require(try await harness.store.fetchMessage(id: harness.messageID))
    #expect(stored.status == .pending)
    #expect(stored.timestamp != preservedTimestamp)
    let parked = try await pendingRow(in: harness)
    #expect(parked.ackDeadline == nil)
    #expect(parked.nextWireAttempt == 0)
    #expect(parked.attemptCount == bumped.attemptCount)
    #expect(await deliveredResolutions(on: harness.service, from: events).isEmpty)

    let before = await harness.transport.sentData.count
    let accepted = Task { await answerCommands(on: harness.transport, after: before, resetPathFails: false) }
    defer { accepted.cancel() }
    _ = try await harness.service.performQueuedDirectMessageSend(
      messageID: harness.messageID,
      to: harness.contact,
      preserveTimestamp: true,
      isResend: true,
      isSingleAttempt: false,
      pendingSendID: harness.pendingID,
      sendLease: nil
    )
    accepted.cancel()
    let commands = await sendCommands(on: harness.transport, after: before)
    #expect(commands.count == 1)
    #expect(commands.first.map { $0.count > 2 ? $0[2] : 255 } == 0)
    let retried = try #require(try await harness.store.fetchMessage(id: harness.messageID))
    #expect(retried.timestamp == stored.timestamp)
  }

  @Test
  func `a not submitted resend restores delivery and keeps the pending row`() async throws {
    let harness = try await makePreservedHarness(startingAttempt: 0, budget: .singleAttempt)
    defer { Task { await harness.session.stop() } }
    try await harness.store.updateMessageStatus(id: harness.messageID, status: .delivered)
    let bumped = try #require(try await harness.store.preparePendingSendAttempt(messageID: harness.messageID))
    #expect(bumped.attemptCount == 1)
    let events = harness.service.statusEvents()
    await harness.transport.rejectNextSendBeforeSubmission()
    let error = await #expect(throws: MeshCoreError.self) {
      try await harness.service.performQueuedDirectMessageSend(
        messageID: harness.messageID,
        to: harness.contact,
        preserveTimestamp: false,
        isResend: true,
        isSingleAttempt: false,
        pendingSendID: harness.pendingID,
        sendLease: nil
      )
    }
    guard case .notConnected? = error else {
      Issue.record("Expected not submitted, got \(String(describing: error))")
      return
    }
    let stored = try #require(try await harness.store.fetchMessage(id: harness.messageID))
    #expect(stored.status == .delivered)
    #expect(stored.timestamp == preservedTimestamp)
    let parked = try await pendingRow(in: harness)
    #expect(parked.ackDeadline == nil)
    #expect(parked.nextWireAttempt == 0)
    #expect(parked.attemptCount == PersistenceStore.initialAttemptCount)
    #expect(await deliveredResolutions(on: harness.service, from: events) == [harness.messageID])
  }

  @Test
  func `a preserved drain of a delivered message sends nothing`() async throws {
    let harness = try await makePreservedHarness(startingAttempt: 1, budget: .default)
    defer { Task { await harness.session.stop() } }
    try await harness.store.updateMessageStatus(id: harness.messageID, status: .delivered)
    let before = await harness.transport.sentData.count
    let returned = try await harness.service.performQueuedDirectMessageSend(
      messageID: harness.messageID,
      to: harness.contact,
      preserveTimestamp: true,
      isResend: true,
      isSingleAttempt: false,
      pendingSendID: harness.pendingID,
      sendLease: nil
    )
    #expect(returned.status == .delivered)
    #expect(returned.timestamp == 1_700_000_000)
    let commands = await sendCommands(on: harness.transport, after: before)
    #expect(commands.isEmpty)
    let stored = try #require(try await harness.store.fetchMessage(id: harness.messageID))
    #expect(stored.timestamp == 1_700_000_000)
  }

  @Test
  func `the retry loop reads the budget provider once`() async throws {
    let calls = OSAllocatedUnfairLock(initialState: 0)
    let observed = try await exhaust(budgetProvider: {
      calls.withLock { count in
        count += 1
        return count == 1
          ? .singleAttempt
          : .default
      }
    })
    #expect(calls.withLock { $0 } == 1)
    #expect(observed.attempts == [0])
    #expect(observed.resetIndexes.isEmpty)
  }

  private struct Observation {
    var attempts: [UInt8]
    var resetIndexes: [Int]
    var retryMaxAttempts: [Int]
    var timestamps: [UInt32] = []
    var restamped: UInt32 = 0
    var messageIDs: [UUID] = []
  }

  private func exhaust(
    budget: DMSendBudget,
    resetPathFails: Bool = false
  ) async throws -> Observation {
    try await exhaust(budgetProvider: { budget }, resetPathFails: resetPathFails)
  }

  private func exhaust(
    budgetProvider: @escaping @Sendable () -> DMSendBudget,
    resetPathFails: Bool = false
  ) async throws -> Observation {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport, configuration: SessionConfiguration(defaultTimeout: 10))
    let start = Task { try await session.start() }
    try await waitUntil("session should send app start") { await transport.sentData.count == 1 }
    await transport.simulateReceive(selfInfoPacket(publicKey: senderKey))
    try await start.value
    defer { Task { await session.stop() } }

    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let service = MessageService(
      session: session,
      dataStore: store,
      contactService: nil,
      config: MessageServiceConfig(minTimeout: 0),
      dmSendBudget: budgetProvider
    )
    let events = service.statusEvents()
    let contact = ContactDTO.testContact(radioID: UUID(), publicKey: Data(repeating: 0x42, count: 32))
    let responder = Task { await answerCommands(on: transport, after: 1, resetPathFails: resetPathFails) }
    defer { responder.cancel() }

    let sent = try await service.sendMessageWithRetry(text: "budget", to: contact)
    #expect(sent.status == .sent)
    responder.cancel()

    let packets = await transport.sentData
    var attempts: [UInt8] = []
    var resetIndexes: [Int] = []
    var directBeforeReset = 0
    for packet in packets {
      switch packet.first {
      case CommandCode.sendMessage.rawValue:
        attempts.append(packet.count > 2 ? packet[2] : 255)
        if resetIndexes.isEmpty { directBeforeReset += 1 }
      case CommandCode.resetPath.rawValue:
        resetIndexes.append(directBeforeReset)
      default:
        break
      }
    }
    let retries = await service.drainStatusEvents(events).retryUpdates.map(\.maxAttempts)
    return Observation(attempts: attempts, resetIndexes: resetIndexes, retryMaxAttempts: retries)
  }

  private struct PreservedHarness {
    let service: MessageService
    let store: PersistenceStore
    let transport: MockTransport
    let session: MeshCoreSession
    let contact: ContactDTO
    let messageID: UUID
    let pendingID: UUID
  }

  private func makePreservedHarness(
    startingAttempt: UInt8,
    budget: DMSendBudget,
    config: MessageServiceConfig = MessageServiceConfig(minTimeout: 0)
  ) async throws -> PreservedHarness {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10)
    )
    let start = Task { try await session.start() }
    try await waitUntil("session should send app start") { await transport.sentData.count == 1 }
    await transport.simulateReceive(selfInfoPacket(publicKey: senderKey))
    try await start.value

    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let service = MessageService(
      session: session,
      dataStore: store,
      contactService: nil,
      config: config,
      dmSendBudget: { budget }
    )
    let contact = ContactDTO.testContact(radioID: UUID(), publicKey: Data(repeating: 0x42, count: 32))
    let message = try await service.createPendingMessage(text: "budget", to: contact)
    try await store.updateMessageTimestamp(id: message.id, timestamp: preservedTimestamp)
    let pendingID = UUID()
    _ = try await store.insertPendingSendAssigningSequence(PendingSendDTO(
      id: pendingID,
      envelope: DirectMessageEnvelope(messageID: message.id, contactID: contact.id),
      radioID: contact.radioID
    ))
    #expect(try await store.setPendingSendWireAttempt(id: pendingID, to: startingAttempt))
    return PreservedHarness(
      service: service,
      store: store,
      transport: transport,
      session: session,
      contact: contact,
      messageID: message.id,
      pendingID: pendingID
    )
  }

  private func deliveredResolutions(on service: MessageService, from events: AsyncStream<MessageStatusEvent>) async -> [UUID] {
    await service.drainStatusEvents(events).compactMap { event in
      guard case let .statusResolved(id, status, roundTripTime) = event,
            status == .delivered, roundTripTime == nil else { return nil }
      return id
    }
  }

  private func pendingRow(in harness: PreservedHarness) async throws -> PendingSendDTO {
    let rows = try await harness.store.fetchPendingSends(radioID: harness.contact.radioID)
    return try #require(rows.first { $0.id == harness.pendingID })
  }

  private func sendCommands(on transport: MockTransport, after answered: Int) async -> [Data] {
    let packets = await transport.sentData
    return packets.dropFirst(answered).filter { $0.first == CommandCode.sendMessage.rawValue }
  }

  private func drainPreserved(startingAttempt: UInt8, budget: DMSendBudget) async throws -> Observation {
    let harness = try await makePreservedHarness(startingAttempt: startingAttempt, budget: budget)
    defer { Task { await harness.session.stop() } }
    let responder = Task { await answerCommands(on: harness.transport, after: 1, resetPathFails: false) }
    defer { responder.cancel() }
    _ = try await harness.service.performQueuedDirectMessageSend(
      messageID: harness.messageID,
      to: harness.contact,
      preserveTimestamp: true,
      isResend: false,
      isSingleAttempt: false,
      pendingSendID: harness.pendingID,
      sendLease: nil
    )
    responder.cancel()

    let packets = await harness.transport.sentData
    var attempts: [UInt8] = []
    var timestamps: [UInt32] = []
    var resetIndexes: [Int] = []
    var directBeforeReset = 0
    for packet in packets {
      switch packet.first {
      case CommandCode.sendMessage.rawValue:
        attempts.append(packet.count > 2 ? packet[2] : 255)
        if packet.count >= 7 {
          let raw = packet.subdata(in: 3..<7).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }
          timestamps.append(UInt32(littleEndian: raw))
        }
        if resetIndexes.isEmpty { directBeforeReset += 1 }
      case CommandCode.resetPath.rawValue:
        resetIndexes.append(directBeforeReset)
      default:
        break
      }
    }
    let messages = try await harness.store.fetchMessages(contactID: harness.contact.id)
    let stored = try #require(try await harness.store.fetchMessage(id: harness.messageID))
    return Observation(
      attempts: attempts,
      resetIndexes: resetIndexes,
      retryMaxAttempts: [],
      timestamps: timestamps,
      restamped: stored.timestamp,
      messageIDs: messages.map(\.id)
    )
  }

  private func answerCommands(
    on transport: MockTransport,
    after answered: Int,
    resetPathFails: Bool,
    rejectSend: Bool = false,
    tableFull: Bool = false
  ) async {
    var responded = answered
    while !Task.isCancelled {
      let packets = await transport.sentData
      guard packets.count > responded else {
        try? await Task.sleep(for: .milliseconds(2))
        continue
      }
      for packet in packets[responded...] {
        switch packet.first {
        case CommandCode.resetPath.rawValue:
          if resetPathFails {
            await transport.simulateReceive(Data([ResponseCode.error.rawValue]))
          } else {
            await transport.simulateOK()
          }
        case CommandCode.getContactByKey.rawValue:
          await transport.simulateReceive(Data([ResponseCode.error.rawValue]))
        case CommandCode.sendMessage.rawValue where rejectSend || tableFull:
          let code = tableFull ? FirmwareDeviceErrorCode.directMessageTableFull : rejectedDirectMessageCode
          await transport.simulateReceive(Data([ResponseCode.error.rawValue, code]))
        default:
          var frame = Data([ResponseCode.messageSent.rawValue, 0])
          frame.append(Data([0xA1, 0xB2, 0xC3, 0xD4]))
          frame.append(contentsOf: withUnsafeBytes(of: UInt32(10).littleEndian) { Array($0) })
          await transport.simulateReceive(frame)
        }
      }
      responded = packets.count
    }
  }

  private func selfInfoPacket(publicKey: Data) -> Data {
    var payload = Data([1, 22, 22])
    payload.append(publicKey)
    payload.append(Data(repeating: 0, count: 8))
    payload.append(contentsOf: [0, 0, 0])
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    payload.append(contentsOf: [7, 5])
    payload.append(contentsOf: "Test".utf8)
    var packet = Data([ResponseCode.selfInfo.rawValue])
    packet.append(payload)
    return packet
  }
}
