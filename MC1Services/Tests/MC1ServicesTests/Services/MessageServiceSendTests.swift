import Testing
import Foundation
import MeshCoreTestSupport
@testable import MC1Services
@testable import MeshCore

@Suite("MessageService Send Tests")
struct MessageServiceSendTests {

    private let testDeviceID = UUID()

    // MARK: - sendDirectMessage

    @Test("sendDirectMessage throws invalidRecipient for repeater contacts")
    func sendDirectMessageRejectsRepeater() async throws {
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

    @Test("sendDirectMessage throws messageTooLong for oversized text")
    func sendDirectMessageRejectsLongText() async throws {
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

    @Test("sendDirectMessage saves message to dataStore before send attempt")
    func sendDirectMessageSavesFirst() async throws {
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

    @Test("sendMessageWithRetry throws invalidRecipient for repeater contacts")
    func sendMessageWithRetryRejectsRepeater() async throws {
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

    @Test("sendMessageWithRetry throws messageTooLong for oversized text")
    func sendMessageWithRetryRejectsLongText() async throws {
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

    @Test("createPendingMessage creates message with pending status")
    func createPendingMessageStatus() async throws {
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

    @Test("createPendingMessage throws invalidRecipient for repeater")
    func createPendingMessageRejectsRepeater() async throws {
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

    @Test("createPendingMessage throws messageTooLong for oversized text")
    func createPendingMessageRejectsLongText() async throws {
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

    @Test("createPendingMessage returns DTO with correct fields")
    func createPendingMessageFields() async throws {
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

    @Test("createPendingMessage stamps lastMessageDate so a first DM appears in the chat list before any send succeeds")
    func createPendingMessageMakesConversationVisible() async throws {
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

    @Test("sendPendingDirectMessage rejects concurrent send for same messageID")
    func sendPendingDirectMessageRejectsConcurrent() async throws {
        let (service, _) = try await MessageService.createForTesting()
        let contact = ContactDTO.testContact(radioID: testDeviceID)
        let messageID = UUID()

        await service.insertInFlightRetryForTest(messageID)

        try await #expect {
            _ = try await service.sendPendingDirectMessage(messageID: messageID, to: contact)
        } throws: { error in
            guard let e = error as? MessageServiceError, case .sendFailed(let msg) = e else { return false }
            return msg.contains("already in progress")
        }
    }

    @Test("sendPendingDirectMessage throws when message not found")
    func sendPendingDirectMessageThrowsWhenNotFound() async throws {
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

    @Test("sendChannelMessage throws messageTooLong for oversized text")
    func sendChannelMessageRejectsLongText() async throws {
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

    @Test("sendChannelMessage saves message to dataStore before send attempt")
    func sendChannelMessageSavesFirst() async throws {
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

    @Test("createPendingChannelMessage saves to dataStore with pending status")
    func createPendingChannelMessageSavesWithPendingStatus() async throws {
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

    @Test("createPendingChannelMessage throws messageTooLong for oversized text")
    func createPendingChannelMessageRejectsLongText() async throws {
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

    @Test("sendPendingChannelMessage throws when message not found")
    func sendPendingChannelMessageThrowsWhenNotFound() async throws {
        let (service, _) = try await MessageService.createForTesting()

        try await #expect {
            try await service.sendPendingChannelMessage(messageID: UUID())
        } throws: { error in
            guard let e = error as? MessageServiceError, case .sendFailed = e else { return false }
            return true
        }
    }

    @Test("sendPendingChannelMessage sets failed status on send error")
    func sendPendingChannelMessageSetsFailedOnError() async throws {
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

    @Test("resendChannelMessage throws when message not found")
    func resendChannelMessageThrowsWhenNotFound() async throws {
        let (service, _) = try await MessageService.createForTesting()

        try await #expect {
            try await service.resendChannelMessage(messageID: UUID())
        } throws: { error in
            guard let e = error as? MessageServiceError, case .sendFailed = e else { return false }
            return true
        }
    }

    @Test("resendChannelMessage throws when message is not a channel message")
    func resendChannelMessageRejectsNonChannel() async throws {
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

    @Test("resendChannelMessage writes .sent before firing messageResentHandler and refreshes counts")
    @MainActor
    func resendChannelMessageFiresResentHandlerAfterDBWrite() async throws {
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
        let service = MessageService(session: session, dataStore: dataStore)

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

        let tracker = MessageResentTracker()
        await service.setMessageResentHandler { id in
            await tracker.record(id)
        }

        let resendTask = Task { try await service.resendChannelMessage(messageID: messageID) }

        try await waitUntil("resend should send CMD_SEND_CHANNEL_MSG") {
            await transport.sentData.count == 2
        }
        await transport.simulateOK()

        _ = try await resendTask.value

        let recorded = await tracker.resentIDs
        #expect(recorded == [messageID], "messageResentHandler must fire exactly once with the resent ID")

        let stored = try await dataStore.fetchMessage(id: messageID)
        #expect(stored?.status == .sent, "resend must write .sent to the DB before firing the handler")
        #expect(stored?.heardRepeats == 0, "resend must reset heardRepeats to 0")
        #expect(stored?.sendCount == 2, "resend must increment sendCount from 1 to 2")
    }

    @Test("resendDirectMessage increments sendCount and fires messageResentHandler on a successful resend")
    @MainActor
    func resendDirectMessageBumpsSendCountAndFiresResentHandler() async throws {
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
        let service = MessageService(session: session, dataStore: dataStore)

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

        let tracker = MessageResentTracker()
        await service.setMessageResentHandler { id in
            await tracker.record(id)
        }

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
        msgSent.append(uint32Bytes(5_000))
        await transport.simulateReceive(msgSent)

        _ = try await resendTask.value

        let recorded = await tracker.resentIDs
        #expect(recorded == [messageID],
                "messageResentHandler must fire exactly once on successful DM resend")

        let stored = try await dataStore.fetchMessage(id: messageID)
        #expect(stored?.sendCount == 2,
                "successful resendDirectMessage must increment sendCount from 1 to 2")
    }

    @Test("sendPendingDirectMessage does not bump sendCount or fire messageResentHandler on first send")
    @MainActor
    func sendPendingDirectMessageDoesNotBumpSendCount() async throws {
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
        let service = MessageService(session: session, dataStore: dataStore)

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

        let tracker = MessageResentTracker()
        await service.setMessageResentHandler { id in
            await tracker.record(id)
        }

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
        msgSent.append(uint32Bytes(5_000))
        await transport.simulateReceive(msgSent)

        _ = try await sendTask.value

        let recorded = await tracker.resentIDs
        #expect(recorded.isEmpty,
                "messageResentHandler must not fire on first send")

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

    @Test("sendDirectMessage tracks pending ACK before session.sendMessage so the listener cannot race")
    func sendDirectMessageTracksPendingAckBeforeSend() async throws {
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

    @Test("sendMessageWithRetry tracks pending ACK before session.sendMessage")
    func sendMessageWithRetryTracksPendingAckBeforeSend() async throws {
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

    @Test("failMessageAndRethrow does not downgrade a delivered DB row")
    func failMessageAndRethrowDoesNotDowngradeDelivered() async throws {
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

    @Test("finalizeSend exhaustion does not downgrade a delivered DB row")
    func finalizeSendExhaustionDoesNotDowngradeDelivered() async throws {
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
}
