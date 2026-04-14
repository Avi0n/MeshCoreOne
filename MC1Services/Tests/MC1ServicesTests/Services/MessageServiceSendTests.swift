import Testing
import Foundation
import MeshCoreTestSupport
@testable import MC1Services

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

    // MARK: - retryDirectMessage

    @Test("retryDirectMessage rejects concurrent retry for same messageID")
    func retryDirectMessageRejectsConcurrent() async throws {
        let (service, _) = try await MessageService.createForTesting()
        let contact = ContactDTO.testContact(radioID: testDeviceID)
        let messageID = UUID()

        await service.insertInFlightRetryForTest(messageID)

        try await #expect {
            _ = try await service.retryDirectMessage(messageID: messageID, to: contact)
        } throws: { error in
            guard let e = error as? MessageServiceError, case .sendFailed(let msg) = e else { return false }
            return msg.contains("already in progress")
        }
    }

    @Test("retryDirectMessage throws when message not found")
    func retryDirectMessageThrowsWhenNotFound() async throws {
        let (service, _) = try await MessageService.createForTesting()
        let contact = ContactDTO.testContact(radioID: testDeviceID)

        try await #expect {
            _ = try await service.retryDirectMessage(messageID: UUID(), to: contact)
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
}
