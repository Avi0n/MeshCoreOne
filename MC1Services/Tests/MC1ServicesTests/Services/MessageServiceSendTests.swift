import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("MessageService Send Tests")
struct MessageServiceSendTests {
  private let testDeviceID = UUID()

  // MARK: - sendDirectMessage

  @Test
  func `sendDirectMessage throws invalidRecipient for repeater contacts`() async throws {
    let (service, _) = try await MessageService.createForTesting()
    let repeater = ContactDTO.testContact(
      radioID: testDeviceID,
      typeRawValue: ContactType.repeater.rawValue
    )

    try await #expect {
      _ = try await service.sendDirectMessage(text: "Hello", to: repeater)
    } throws: { error in
      guard let e = error as? MessageServiceError, case .invalidRecipient = e else { return false }
      return true
    }
  }

  @Test
  func `sendDirectMessage throws messageTooLong for oversized text`() async throws {
    let (service, _) = try await MessageService.createForTesting()
    let contact = ContactDTO.testContact(radioID: testDeviceID)
    let longText = String(repeating: "a", count: ProtocolLimits.maxDirectMessageLength + 1)

    try await #expect {
      _ = try await service.sendDirectMessage(text: longText, to: contact)
    } throws: { error in
      guard let e = error as? MessageServiceError, case .messageTooLong = e else { return false }
      return true
    }
  }

  @Test
  func `sendDirectMessage saves message to dataStore before send attempt`() async throws {
    let (service, dataStore) = try await MessageService.createForTesting()
    let contact = ContactDTO.testContact(radioID: testDeviceID)
    do {
      _ = try await service.sendDirectMessage(text: "Hello", to: contact)
    } catch {
      // Expected — session not started
    }

    let messages = try await dataStore.fetchMessages(contactID: contact.id, limit: 10, offset: 0)
    #expect(!messages.isEmpty, "Message should be saved before send attempt")
    #expect(messages.first?.text == "Hello")
    #expect(messages.first?.direction == .outgoing)
  }

  // MARK: - sendMessageWithRetry

  @Test
  func `sendMessageWithRetry throws invalidRecipient for repeater contacts`() async throws {
    let (service, _) = try await MessageService.createForTesting()
    let repeater = ContactDTO.testContact(
      radioID: testDeviceID,
      typeRawValue: ContactType.repeater.rawValue
    )

    try await #expect {
      _ = try await service.sendMessageWithRetry(text: "Hello", to: repeater)
    } throws: { error in
      guard let e = error as? MessageServiceError, case .invalidRecipient = e else { return false }
      return true
    }
  }

  @Test
  func `sendMessageWithRetry throws messageTooLong for oversized text`() async throws {
    let (service, _) = try await MessageService.createForTesting()
    let contact = ContactDTO.testContact(radioID: testDeviceID)
    let longText = String(repeating: "a", count: ProtocolLimits.maxDirectMessageLength + 1)

    try await #expect {
      _ = try await service.sendMessageWithRetry(text: longText, to: contact)
    } throws: { error in
      guard let e = error as? MessageServiceError, case .messageTooLong = e else { return false }
      return true
    }
  }

  // MARK: - createPendingMessage

  @Test
  func `createPendingMessage creates message with pending status`() async throws {
    let (service, dataStore) = try await MessageService.createForTesting()
    let contact = ContactDTO.testContact(radioID: testDeviceID)

    let message = try await service.createPendingMessage(text: "Pending", to: contact)

    #expect(message.status == .pending)
    #expect(message.direction == .outgoing)
    #expect(message.text == "Pending")
    #expect(message.contactID == contact.id)

    let fetched = try await dataStore.fetchMessage(id: message.id)
    #expect(fetched != nil)
    #expect(fetched?.status == .pending)
  }

  @Test
  func `createPendingMessage throws invalidRecipient for repeater`() async throws {
    let (service, _) = try await MessageService.createForTesting()
    let repeater = ContactDTO.testContact(
      radioID: testDeviceID,
      typeRawValue: ContactType.repeater.rawValue
    )

    try await #expect {
      _ = try await service.createPendingMessage(text: "Test", to: repeater)
    } throws: { error in
      guard let e = error as? MessageServiceError, case .invalidRecipient = e else { return false }
      return true
    }
  }

  @Test
  func `createPendingMessage throws messageTooLong for oversized text`() async throws {
    let (service, _) = try await MessageService.createForTesting()
    let contact = ContactDTO.testContact(radioID: testDeviceID)
    let longText = String(repeating: "a", count: ProtocolLimits.maxDirectMessageLength + 1)

    try await #expect {
      _ = try await service.createPendingMessage(text: longText, to: contact)
    } throws: { error in
      guard let e = error as? MessageServiceError, case .messageTooLong = e else { return false }
      return true
    }
  }

  @Test
  func `createPendingMessage returns DTO with correct fields`() async throws {
    let (service, _) = try await MessageService.createForTesting()
    let contactID = UUID()
    let contact = ContactDTO.testContact(id: contactID, radioID: testDeviceID)

    let message = try await service.createPendingMessage(
      text: "Hello world",
      to: contact,
      textType: .plain
    )

    #expect(message.text == "Hello world")
    #expect(message.contactID == contactID)
    #expect(message.radioID == testDeviceID)
    #expect(message.direction == .outgoing)
    #expect(message.textType == .plain)
    #expect(message.channelIndex == nil)
  }

  @Test
  func `createPendingMessage stamps lastMessageDate so a first DM appears in the chat list before any send succeeds`() async throws {
    let (service, dataStore) = try await MessageService.createForTesting()
    let contact = ContactDTO.testContact(radioID: testDeviceID, lastMessageDate: nil)
    try await dataStore.saveContact(contact)

    let before = try await dataStore.fetchConversations(radioID: testDeviceID)
    #expect(before.isEmpty)

    _ = try await service.createPendingMessage(text: "First DM", to: contact)

    let after = try await dataStore.fetchConversations(radioID: testDeviceID)
    #expect(after.contains { $0.id == contact.id })
    #expect(after.first { $0.id == contact.id }?.lastMessageDate != nil)
  }

  // MARK: - sendPendingDirectMessage / resendDirectMessage

  @Test
  func `sendPendingDirectMessage rejects concurrent send for same messageID`() async throws {
    let (service, _) = try await MessageService.createForTesting()
    let contact = ContactDTO.testContact(radioID: testDeviceID)
    let messageID = UUID()

    await service.insertInFlightRetryForTest(messageID)

    try await #expect {
      _ = try await service.sendPendingDirectMessage(messageID: messageID, to: contact)
    } throws: { error in
      guard let e = error as? MessageServiceError, case let .sendFailed(msg) = e else { return false }
      return msg.contains("already in progress")
    }
  }

  @Test
  func `sendPendingDirectMessage throws when message not found`() async throws {
    let (service, _) = try await MessageService.createForTesting()
    let contact = ContactDTO.testContact(radioID: testDeviceID)

    try await #expect {
      _ = try await service.sendPendingDirectMessage(messageID: UUID(), to: contact)
    } throws: { error in
      guard let e = error as? MessageServiceError, case .sendFailed = e else { return false }
      return true
    }
  }

  // MARK: - sendChannelMessage

  @Test
  func `sendChannelMessage throws messageTooLong for oversized text`() async throws {
    let (service, _) = try await MessageService.createForTesting()
    let longText = String(repeating: "a", count: ProtocolLimits.maxChannelMessageTotalLength + 1)

    try await #expect {
      _ = try await service.sendChannelMessage(
        text: longText,
        channelIndex: 0,
        radioID: testDeviceID
      )
    } throws: { error in
      guard let e = error as? MessageServiceError, case .messageTooLong = e else { return false }
      return true
    }
  }

  @Test
  func `sendChannelMessage saves message to dataStore before send attempt`() async throws {
    let (service, dataStore) = try await MessageService.createForTesting()
    do {
      _ = try await service.sendChannelMessage(
        text: "Hello channel",
        channelIndex: 0,
        radioID: testDeviceID
      )
    } catch {
      // Expected — session not started
    }

    let messages = try await dataStore.fetchMessages(
      radioID: testDeviceID, channelIndex: 0, limit: 10, offset: 0
    )
    #expect(!messages.isEmpty, "Message should be saved before send attempt")
    #expect(messages.first?.text == "Hello channel")
    #expect(messages.first?.direction == .outgoing)
    #expect(messages.first?.status == .failed, "Message should be marked failed after send error")
  }

  // MARK: - createPendingChannelMessage

  @Test
  func `createPendingChannelMessage saves to dataStore with pending status`() async throws {
    let (service, dataStore) = try await MessageService.createForTesting()

    let message = try await service.createPendingChannelMessage(
      text: "Hello channel",
      channelIndex: 0,
      radioID: testDeviceID
    )

    #expect(message.status == .pending)
    #expect(message.direction == .outgoing)
    #expect(message.text == "Hello channel")
    #expect(message.channelIndex == 0)
    #expect(message.radioID == testDeviceID)
    #expect(message.contactID == nil)

    let stored = try await dataStore.fetchMessage(id: message.id)
    #expect(stored != nil, "Message should be persisted to dataStore")
    #expect(stored?.status == .pending)
  }

  @Test
  func `createPendingChannelMessage throws messageTooLong for oversized text`() async throws {
    let (service, _) = try await MessageService.createForTesting()
    let longText = String(repeating: "a", count: ProtocolLimits.maxChannelMessageTotalLength + 1)

    try await #expect {
      _ = try await service.createPendingChannelMessage(
        text: longText,
        channelIndex: 0,
        radioID: testDeviceID
      )
    } throws: { error in
      guard let e = error as? MessageServiceError, case .messageTooLong = e else { return false }
      return true
    }
  }

  // MARK: - sendPendingChannelMessage

  @Test
  func `sendPendingChannelMessage throws when message not found`() async throws {
    let (service, _) = try await MessageService.createForTesting()

    try await #expect {
      try await service.sendPendingChannelMessage(messageID: UUID())
    } throws: { error in
      guard let e = error as? MessageServiceError, case .sendFailed = e else { return false }
      return true
    }
  }

  @Test
  func `sendPendingChannelMessage sets failed status on send error`() async throws {
    let (service, dataStore) = try await MessageService.createForTesting()

    let message = try await service.createPendingChannelMessage(
      text: "Hello channel",
      channelIndex: 0,
      radioID: testDeviceID
    )
    #expect(message.status == .pending)

    do {
      try await service.sendPendingChannelMessage(messageID: message.id)
    } catch {
      // Expected — session not started
    }

    let stored = try await dataStore.fetchMessage(id: message.id)
    #expect(stored?.status == .failed, "Message should be marked failed after send error")
  }

  // MARK: - resendChannelMessage

  @Test
  func `resendChannelMessage throws when message not found`() async throws {
    let (service, _) = try await MessageService.createForTesting()

    try await #expect {
      try await service.resendChannelMessage(messageID: UUID())
    } throws: { error in
      guard let e = error as? MessageServiceError, case .sendFailed = e else { return false }
      return true
    }
  }

  @Test
  func `resendChannelMessage throws when message is not a channel message`() async throws {
    let (service, dataStore) = try await MessageService.createForTesting()
    let messageID = UUID()

    let dm = MessageDTO.testDirectMessage(id: messageID, radioID: testDeviceID)
    try await dataStore.saveMessage(dm)

    try await #expect {
      try await service.resendChannelMessage(messageID: messageID)
    } throws: { error in
      guard let e = error as? MessageServiceError, case .sendFailed = e else { return false }
      return true
    }
  }

  @Test
  @MainActor
  func `resendChannelMessage writes .sent before broadcasting .resent and refreshes counts`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    defer { Task { await session.stop() } }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let service = MessageService(session: session, dataStore: dataStore, contactService: nil)

    let messageID = UUID()
    let failed = MessageDTO.testChannelMessage(
      id: messageID,
      radioID: testDeviceID,
      channelIndex: 0,
      status: .failed,
      heardRepeats: 3,
      sendCount: 1
    )
    try await dataStore.saveMessage(failed)

    let statusEvents = service.statusEvents()

    let resendTask = Task { try await service.resendChannelMessage(messageID: messageID) }

    try await waitUntil("resend should send CMD_SEND_CHANNEL_MSG") {
      await transport.sentData.count == 2
    }
    await transport.simulateOK()

    _ = try await resendTask.value

    let recorded = await service.drainStatusEvents(statusEvents).resentIDs
    #expect(recorded == [messageID], ".resent must broadcast exactly once with the resent ID")

    let stored = try await dataStore.fetchMessage(id: messageID)
    #expect(stored?.status == .sent, "resend must write .sent to the DB before broadcasting .resent")
    #expect(stored?.heardRepeats == 0, "resend must reset heardRepeats to 0")
    #expect(stored?.sendCount == 2, "resend must increment sendCount from 1 to 2")
  }

  @Test
  @MainActor
  func `resendDirectMessage increments sendCount and broadcasts .resent on a successful resend`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10)
    )
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    defer { Task { await session.stop() } }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let service = MessageService(session: session, dataStore: dataStore, contactService: nil)

    let messageID = UUID()
    let contactID = UUID()
    let radioID = testDeviceID
    let contact = ContactDTO.testContact(id: contactID, radioID: radioID)

    let delivered = MessageDTO.testDirectMessage(
      id: messageID,
      radioID: radioID,
      contactID: contactID,
      status: .delivered,
      sendCount: 1
    )
    try await dataStore.saveMessage(delivered)

    let statusEvents = service.statusEvents()

    // Pre-populate the pending-ack entry as already delivered so the
    // retry loop short-circuits after sendMessage returns.
    let ackCode = Data([0xAB, 0xCD, 0xEF, 0x12])
    await service.setPendingAckForTest(
      PendingAck(
        messageID: messageID,
        contactID: contactID,
        ackCodes: [ackCode],
        sentAt: Date(),
        timeout: 30,
        isDelivered: true
      )
    )

    let resendTask = Task {
      try await service.resendDirectMessage(messageID: messageID, to: contact)
    }

    try await waitUntil("resend should send CMD_SEND_TXT_MSG") {
      await transport.sentData.count == 2
    }

    var msgSent = Data([ResponseCode.messageSent.rawValue])
    msgSent.append(0)
    msgSent.append(ackCode)
    msgSent.append(uint32Bytes(5000))
    await transport.simulateReceive(msgSent)

    _ = try await resendTask.value

    let recorded = await service.drainStatusEvents(statusEvents).resentIDs
    #expect(recorded == [messageID],
            ".resent must broadcast exactly once on successful DM resend")

    let stored = try await dataStore.fetchMessage(id: messageID)
    #expect(stored?.sendCount == 2,
            "successful resendDirectMessage must increment sendCount from 1 to 2")
  }

  @Test
  @MainActor
  func `sendPendingDirectMessage does not bump sendCount or broadcast .resent on first send`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10)
    )
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    defer { Task { await session.stop() } }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let service = MessageService(session: session, dataStore: dataStore, contactService: nil)

    let messageID = UUID()
    let contactID = UUID()
    let radioID = testDeviceID
    let contact = ContactDTO.testContact(id: contactID, radioID: radioID)

    let pending = MessageDTO.testDirectMessage(
      id: messageID,
      radioID: radioID,
      contactID: contactID,
      status: .pending,
      sendCount: 1
    )
    try await dataStore.saveMessage(pending)

    let statusEvents = service.statusEvents()

    // Pre-populate the pending-ack entry as already delivered so the
    // retry loop short-circuits after sendMessage returns.
    let ackCode = Data([0xAB, 0xCD, 0xEF, 0x12])
    await service.setPendingAckForTest(
      PendingAck(
        messageID: messageID,
        contactID: contactID,
        ackCodes: [ackCode],
        sentAt: Date(),
        timeout: 30,
        isDelivered: true
      )
    )

    let sendTask = Task {
      try await service.sendPendingDirectMessage(messageID: messageID, to: contact)
    }

    try await waitUntil("send should send CMD_SEND_TXT_MSG") {
      await transport.sentData.count == 2
    }

    var msgSent = Data([ResponseCode.messageSent.rawValue])
    msgSent.append(0)
    msgSent.append(ackCode)
    msgSent.append(uint32Bytes(5000))
    await transport.simulateReceive(msgSent)

    _ = try await sendTask.value

    let recorded = await service.drainStatusEvents(statusEvents).resentIDs
    #expect(recorded.isEmpty,
            ".resent must not broadcast on first send")

    let stored = try await dataStore.fetchMessage(id: messageID)
    #expect(stored?.sendCount == 1,
            "successful sendPendingDirectMessage must leave sendCount at 1")
  }

  private func makeSelfInfoPacket() -> Data {
    var payload = Data()
    payload.append(1)
    payload.append(22)
    payload.append(22)
    payload.append(Data(repeating: 0x01, count: 32))
    payload.append(int32Bytes(0))
    payload.append(int32Bytes(0))
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(uint32Bytes(915_000))
    payload.append(uint32Bytes(125_000))
    payload.append(7)
    payload.append(5)
    payload.append(contentsOf: "Test".utf8)

    var packet = Data([ResponseCode.selfInfo.rawValue])
    packet.append(payload)
    return packet
  }

  private func int32Bytes(_ value: Double) -> Data {
    withUnsafeBytes(of: Int32(value.rounded()).littleEndian) { Data($0) }
  }

  private func uint32Bytes(_ value: UInt32) -> Data {
    withUnsafeBytes(of: value.littleEndian) { Data($0) }
  }

  @Test
  func `sendDirectMessage tracks pending ACK before session.sendMessage so the listener cannot race`() async throws {
    let (service, _) = try await MessageService.createForTesting(defaultTimeout: 10, connectTransport: true)

    // Seed selfInfo so the precompute step can read currentSelfInfo.publicKey
    // without simulating an APP_START round-trip.
    await service.installSelfInfoForTest(publicKey: Data(repeating: 0xFE, count: 32))

    let contact = ContactDTO.testContact()

    // The mock transport never emits a messageSent event, so sendDirectMessage
    // suspends inside session.sendMessage for the full defaultTimeout, holding
    // the speculative pending-ack entry that trackPendingAck adds *before* the
    // send. Poll for that entry with a generous ceiling: a correct ordering
    // surfaces it near-instantly, while a regression that tracked after
    // session.sendMessage would be blocked behind the send's timeout and never
    // surface it before the task is cancelled — so this still catches reorders.
    let sendTask = Task {
      try? await service.sendDirectMessage(text: "hi", to: contact)
    }

    try await waitUntil(
      timeout: .seconds(8),
      "trackPendingAck must run before session.sendMessage so a listener ACK cannot race the tracker"
    ) {
      await service.pendingAckCount > 0
    }

    sendTask.cancel()
    _ = await sendTask.value
  }

  @Test
  func `sendMessageWithRetry tracks pending ACK before session.sendMessage`() async throws {
    let (service, _) = try await MessageService.createForTesting(defaultTimeout: 10, connectTransport: true)

    await service.installSelfInfoForTest(publicKey: Data(repeating: 0xFE, count: 32))

    let contact = ContactDTO.testContact()

    let sendTask = Task {
      try? await service.sendMessageWithRetry(text: "hi", to: contact)
    }

    try await waitUntil(
      timeout: .seconds(8),
      "retry-loop precompute must track before session.sendMessage on every attempt"
    ) {
      await service.pendingAckCount > 0
    }

    sendTask.cancel()
    _ = await sendTask.value
  }

  @Test
  func `failMessageAndRethrow does not downgrade a delivered DB row`() async throws {
    let (service, dataStore) = try await MessageService.createForTesting()
    let messageID = UUID()
    let ackCode = Data([0xDD, 0x11, 0x22, 0x33])

    try await dataStore.saveMessage(
      MessageDTO.testDirectMessage(
        id: messageID,
        radioID: testDeviceID,
        status: .delivered,
        ackCode: ackCode.ackCodeUInt32
      )
    )
    await service.setPendingAckForTest(
      PendingAck(
        messageID: messageID,
        contactID: UUID(),
        ackCodes: [ackCode],
        sentAt: Date(),
        timeout: 30,
        isDelivered: true
      )
    )

    await #expect(throws: MessageServiceError.self) {
      try await service.failMessageAndRethrow(
        MeshCoreError.notConnected,
        messageID: messageID
      )
    }

    let stored = try await dataStore.fetchMessage(id: messageID)
    #expect(stored?.status == .delivered,
            "failMessageAndRethrow must not downgrade a delivered row")
  }

  @Test
  func `finalizeSend exhaustion does not downgrade a delivered DB row`() async throws {
    let (service, dataStore) = try await MessageService.createForTesting()
    let messageID = UUID()
    let contactID = UUID()
    let radioID = testDeviceID
    let publicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })
    let ackCode = Data([0xFE, 0xED, 0xFA, 0xCE])

    try await dataStore.saveMessage(
      MessageDTO.testDirectMessage(
        id: messageID,
        radioID: radioID,
        contactID: contactID,
        status: .delivered,
        ackCode: ackCode.ackCodeUInt32
      )
    )
    await service.setPendingAckForTest(
      PendingAck(
        messageID: messageID,
        contactID: contactID,
        ackCodes: [ackCode],
        sentAt: Date(),
        timeout: 30,
        isDelivered: false
      )
    )

    _ = try await service.finalizeSend(
      messageID: messageID,
      contactID: contactID,
      radioID: radioID,
      publicKey: publicKey,
      sentInfo: nil,
      initialPathLength: 0
    )

    let stored = try await dataStore.fetchMessage(id: messageID)
    #expect(stored?.status == .delivered,
            "finalizeSend exhaustion path must not downgrade a delivered row")
  }

  // MARK: - Retry exhaustion leaves .sent, never premature .failed

  @Test
  @MainActor
  func `retry exhaustion leaves the DM .sent with its pending entry alive, never prematurely .failed`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10)
    )
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    defer { Task { await session.stop() } }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    // maxAttempts 2 so the loop writes .retrying once; floodAfter high so no path reset fires.
    let service = MessageService(
      session: session,
      dataStore: dataStore,
      contactService: nil,
      config: MessageServiceConfig(maxAttempts: 2, floodAfter: 5, minTimeout: 0)
    )

    let contact = ContactDTO.testContact(id: UUID(), radioID: testDeviceID)
    let statusEvents = service.statusEvents()
    let ackCode = Data([0xA1, 0xB2, 0xC3, 0xD4])

    // Accept every CMD_SEND_TXT_MSG with a messageSent frame so session.sendMessage
    // returns, but never emit the end-to-end 0x82 ACK, so each attempt's
    // waitForEvent times out and the loop exhausts to nil.
    let responder = Task {
      var responded = 1 // app start already accounted for
      while !Task.isCancelled {
        let count = await transport.sentData.count
        if count > responded {
          responded = count
          var msgSent = Data([ResponseCode.messageSent.rawValue])
          msgSent.append(0)
          msgSent.append(ackCode)
          msgSent.append(uint32Bytes(10)) // ~12ms ack window
          await transport.simulateReceive(msgSent)
        }
        await Task.yield()
      }
    }
    defer { responder.cancel() }

    let sent = try await service.sendMessageWithRetry(text: "exhaust me", to: contact)

    #expect(sent.status == .sent, "retry exhaustion must leave the row .sent, not .failed")
    #expect(try await dataStore.fetchMessage(id: sent.id)?.status == .sent)
    #expect(await service.pendingAckCount == 1,
            "the pending entry must survive so checkExpiredAcks owns the single give-up")

    let events = await service.drainStatusEvents(statusEvents)
    #expect(!events.failedIDs.contains(sent.id), "no premature .failed on retry exhaustion")
    #expect(!events.retryUpdates.isEmpty, "at least one .retrying must have fired (drove >1 attempt)")
    #expect(events.resolvedIDs.contains(sent.id), "the nil branch must yield .statusResolved(.sent)")
  }

  @Test
  @MainActor
  func `a genuine send exception still fails the DM through the outer catch`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10)
    )
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    defer { Task { await session.stop() } }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let service = MessageService(session: session, dataStore: dataStore, contactService: nil)

    let contact = ContactDTO.testContact(id: UUID(), radioID: testDeviceID)

    // Every send after app start throws, simulating a transport drop.
    await transport.failSends(fromSendIndex: 2)

    await #expect(throws: (any Error).self) {
      _ = try await service.sendMessageWithRetry(text: "boom", to: contact)
    }

    let stored = try await dataStore.fetchMessages(contactID: contact.id, limit: 10, offset: 0).first
    #expect(stored?.status == .failed,
            "a genuine send failure must still reach .failed via the outer catch")
  }

  @Test
  @MainActor
  func `checkExpiredAcks cannot fail a DM while its retry loop is still inside waitForEvent`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 20)
    )
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    defer { Task { await session.stop() } }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    // The manual checkExpiredAcks call runs immediately after the send, so
    // the elapsed time since the just-re-stamped sentAt is ~0s, far under the
    // default give-up window; the global checker must not fire while the loop
    // holds a freshly re-stamped entry.
    let service = MessageService(
      session: session,
      dataStore: dataStore,
      contactService: nil,
      config: MessageServiceConfig(maxAttempts: 2, floodAfter: 5)
    )

    let contactID = UUID()
    let contact = ContactDTO.testContact(id: contactID, radioID: testDeviceID)
    let ackCode = Data([0x7A, 0x7B, 0x7C, 0x7D])

    let sendTask = Task { try await service.sendMessageWithRetry(text: "in flight", to: contact) }

    // Attempt 0's send goes out; feed a messageSent with a long ack window so
    // the loop parks in waitForEvent with a just-re-stamped sentAt.
    try await waitUntil("attempt 0 should send") {
      await transport.sentData.count == 2
    }
    var msgSent = Data([ResponseCode.messageSent.rawValue])
    msgSent.append(0)
    msgSent.append(ackCode)
    msgSent.append(uint32Bytes(15000)) // ~18s window: the loop stays parked
    await transport.simulateReceive(msgSent)

    // Readiness: the loop's waitForEvent subscription is active.
    await service.waitForSubscriberCount(1)

    // Run the global checker mid-loop: it must not fail the in-flight DM.
    try await service.checkExpiredAcks()

    let midLoop = try await dataStore.fetchMessages(contactID: contactID, limit: 1, offset: 0).first
    #expect(midLoop?.status != .failed,
            "checkExpiredAcks must not fail a DM whose retry loop is still in flight")
    #expect(await service.pendingAckCount == 1, "the in-flight entry must survive the checker tick")

    // Unblock the loop so it completes cleanly (delivered).
    await session.dispatchForTesting(.acknowledgement(code: ackCode, tripTime: 100))
    let delivered = try await sendTask.value
    #expect(delivered.status == .delivered)
  }

  @Test
  @MainActor
  func `checkExpiredAcks respects a slow-preset per-attempt timeout that exceeds a tiny give-up window`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 20)
    )
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    defer { Task { await session.stop() } }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    // give-up window 1s; per-attempt timeout derives from suggestedTimeoutMs 15_000 (~18s).
    // After a 2s real wait the elapsed exceeds the 1s window but is inside the 18s attempt
    // timeout, so max(1, 18) = 18 and the checker must leave the entry alive.
    let service = MessageService(
      session: session,
      dataStore: dataStore,
      contactService: nil,
      config: MessageServiceConfig(maxAttempts: 2, floodAfter: 5, ackGiveUpWindow: 1)
    )

    let contactID = UUID()
    let contact = ContactDTO.testContact(id: contactID, radioID: testDeviceID)
    let ackCode = Data([0x7A, 0x7B, 0x7C, 0x7D])

    let sendTask = Task { try await service.sendMessageWithRetry(text: "slow preset", to: contact) }

    try await waitUntil("attempt 0 should send") {
      await transport.sentData.count == 2
    }
    var msgSent = Data([ResponseCode.messageSent.rawValue])
    msgSent.append(0)
    msgSent.append(ackCode)
    msgSent.append(uint32Bytes(15000)) // ~18s window: the loop stays parked
    await transport.simulateReceive(msgSent)

    await service.waitForSubscriberCount(1)

    // Wait 2 real seconds so elapsed > 1s window, then run the checker.
    // Under max(1, 18) = 18 the entry must survive.
    try await Task.sleep(for: .seconds(2))
    try await service.checkExpiredAcks()

    let midLoop = try await dataStore.fetchMessages(contactID: contactID, limit: 1, offset: 0).first
    #expect(midLoop?.status != .failed,
            "checkExpiredAcks must not fail a DM still inside its per-attempt ACK timeout")
    #expect(await service.pendingAckCount == 1, "the in-flight entry must survive when timeout > window")

    // Unblock the loop; it returns once the listener flips isDelivered. The
    // in-flight waitForEvent still parks its full per-attempt timeout rather than
    // hanging, and that timeout must exceed the give-up window to stay meaningful.
    await session.dispatchForTesting(.acknowledgement(code: ackCode, tripTime: 100))
    let delivered = try await sendTask.value
    #expect(delivered.status == .delivered)
  }
}
