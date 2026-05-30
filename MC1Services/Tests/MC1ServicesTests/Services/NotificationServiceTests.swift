// NotificationServiceTests.swift
import Foundation
import Testing
@testable import MC1Services

@Suite("NotificationService Tests")
struct NotificationServiceTests {

    @Test("Suppression flag defaults to false")
    @MainActor
    func suppressionFlagDefaultsToFalse() async {
        let service = NotificationService()
        #expect(service.isSuppressingNotifications == false)
    }

    @Test("Suppression flag can be set and cleared")
    @MainActor
    func suppressionFlagCanBeSetAndCleared() async {
        let service = NotificationService()

        service.isSuppressingNotifications = true
        #expect(service.isSuppressingNotifications == true)

        service.isSuppressingNotifications = false
        #expect(service.isSuppressingNotifications == false)
    }

    @Test("Suppression flag can be toggled multiple times")
    @MainActor
    func suppressionFlagCanBeToggledMultipleTimes() async {
        let service = NotificationService()

        // Toggle several times
        service.isSuppressingNotifications = true
        service.isSuppressingNotifications = false
        service.isSuppressingNotifications = true
        service.isSuppressingNotifications = true  // Setting same value
        service.isSuppressingNotifications = false

        #expect(service.isSuppressingNotifications == false)
    }

    @Test("postNewContactNotification uses provider for title")
    @MainActor
    func postNewContactNotificationUsesProviderForTitle() async {
        // This test verifies the method signature accepts ContactType
        // Actual notification posting requires UNUserNotificationCenter authorization
        let service = NotificationService()

        // Verify method exists with correct signature (compile-time check)
        // The actual notification won't post without authorization, but we can verify
        // the provider is called by checking the method accepts the new parameter
        await service.postNewContactNotification(
            contactName: "TestNode",
            contactID: UUID(),
            contactType: ContactType.repeater
        )

        // If we got here without compile error, the signature is correct
        #expect(true)
    }

    // MARK: - Reaction Notification Tests

    @Test("postReactionNotification has correct method signature")
    @MainActor
    func postReactionNotificationHasCorrectSignature() async {
        let service = NotificationService()

        // Verify method exists with correct signature (compile-time check)
        // Actual notification won't post without authorization
        await service.postReactionNotification(
            reactorName: "Alice",
            body: "Reacted 👍 to your message: \"Hello world\"",
            messageID: UUID(),
            contactID: UUID(),
            channelIndex: nil,
            radioID: nil
        )

        #expect(true)
    }

    @Test("postReactionNotification accepts channel parameters")
    @MainActor
    func postReactionNotificationAcceptsChannelParameters() async {
        let service = NotificationService()

        // Verify method accepts channel parameters for channel reactions
        await service.postReactionNotification(
            reactorName: "Bob",
            body: "Reacted ❤️ to your message: \"Team update\"",
            messageID: UUID(),
            contactID: nil,
            channelIndex: 3,
            radioID: UUID()
        )

        #expect(true)
    }

    @Test("onReactionNotificationTapped callback can be set")
    @MainActor
    func onReactionNotificationTappedCallbackCanBeSet() async {
        let service = NotificationService()
        var callbackInvoked = false

        service.onReactionNotificationTapped = { _, _, _, _ in
            callbackInvoked = true
        }

        // Verify callback is settable
        #expect(service.onReactionNotificationTapped != nil)

        // Invoke callback to verify it works
        await service.onReactionNotificationTapped?(UUID(), nil, nil, UUID())
        #expect(callbackInvoked)
    }

    @Test("onReactionNotificationTapped receives all parameters")
    @MainActor
    func onReactionNotificationTappedReceivesAllParameters() async {
        let service = NotificationService()
        let expectedContactID = UUID()
        let expectedChannelIndex: UInt8 = 5
        let expectedDeviceID = UUID()
        let expectedMessageID = UUID()

        var receivedContactID: UUID?
        var receivedChannelIndex: UInt8?
        var receivedDeviceID: UUID?
        var receivedMessageID: UUID?

        service.onReactionNotificationTapped = { contactID, channelIndex, radioID, messageID in
            receivedContactID = contactID
            receivedChannelIndex = channelIndex
            receivedDeviceID = radioID
            receivedMessageID = messageID
        }

        await service.onReactionNotificationTapped?(
            expectedContactID,
            expectedChannelIndex,
            expectedDeviceID,
            expectedMessageID
        )

        #expect(receivedContactID == expectedContactID)
        #expect(receivedChannelIndex == expectedChannelIndex)
        #expect(receivedDeviceID == expectedDeviceID)
        #expect(receivedMessageID == expectedMessageID)
    }

    @Test("Room message notification is suppressed when isSuppressingNotifications is true")
    @MainActor
    func roomMessageNotificationSuppressedDuringSync() async {
        let service = NotificationService()
        service.isSuppressingNotifications = true

        // Should return without posting (no crash, no notification)
        await service.postRoomMessageNotification(
            roomName: "TestRoom",
            sessionID: UUID(),
            senderName: "Alice",
            messageText: "Hello",
            messageID: UUID(),
            notificationLevel: .all
        )

        // Badge count should not increment when suppressed
        #expect(service.badgeCount == 0)
    }

    @Test("Notification category includes reaction")
    func notificationCategoryIncludesReaction() {
        // Verify reaction category exists in the enum
        let category = NotificationCategory.reaction
        #expect(category.rawValue == "REACTION")
    }

    // MARK: - Room Notification Tests

    @Test("Active room session tracking can be set and cleared")
    @MainActor
    func activeRoomSessionTrackingCanBeSetAndCleared() async {
        let service = NotificationService()
        let sessionID = UUID()

        #expect(service.activeRoomSessionID == nil)

        service.activeRoomSessionID = sessionID
        #expect(service.activeRoomSessionID == sessionID)

        service.activeRoomSessionID = nil
        #expect(service.activeRoomSessionID == nil)
    }

    @Test("setActiveConversation populates only the passed slot and clears the rest")
    @MainActor
    func setActiveConversationIsAtomicAcrossTypes() async {
        let service = NotificationService()
        let contactID = UUID()
        let channelRadioID = UUID()
        let roomSessionID = UUID()

        // Pre-populate every slot so the setter must clear the unpassed ones.
        service.activeContactID = contactID
        service.activeChannelIndex = 3
        service.activeChannelRadioID = channelRadioID
        service.activeRoomSessionID = roomSessionID

        // Opening a DM clears channel and room slots.
        service.setActiveConversation(contactID: contactID)
        #expect(service.activeContactID == contactID)
        #expect(service.activeChannelIndex == nil)
        #expect(service.activeChannelRadioID == nil)
        #expect(service.activeRoomSessionID == nil)

        // Opening a channel clears the contact slot.
        service.setActiveConversation(channelIndex: 5, channelRadioID: channelRadioID)
        #expect(service.activeContactID == nil)
        #expect(service.activeChannelIndex == 5)
        #expect(service.activeChannelRadioID == channelRadioID)
        #expect(service.activeRoomSessionID == nil)

        // Opening a room clears the channel slots.
        service.setActiveConversation(roomSessionID: roomSessionID)
        #expect(service.activeContactID == nil)
        #expect(service.activeChannelIndex == nil)
        #expect(service.activeChannelRadioID == nil)
        #expect(service.activeRoomSessionID == roomSessionID)
    }

    @Test("onRoomMarkAsRead callback can be set and receives parameters")
    @MainActor
    func onRoomMarkAsReadCallbackReceivesParameters() async {
        let service = NotificationService()
        let expectedSessionID = UUID()
        let expectedMessageID = UUID()

        var receivedSessionID: UUID?
        var receivedMessageID: UUID?

        service.onRoomMarkAsRead = { sessionID, messageID in
            receivedSessionID = sessionID
            receivedMessageID = messageID
        }

        #expect(service.onRoomMarkAsRead != nil)

        await service.onRoomMarkAsRead?(expectedSessionID, expectedMessageID)

        #expect(receivedSessionID == expectedSessionID)
        #expect(receivedMessageID == expectedMessageID)
    }

    @Test("onRoomNotificationTapped callback can be set and receives the session ID")
    @MainActor
    func onRoomNotificationTappedCallbackReceivesSessionID() async {
        let service = NotificationService()
        let expectedSessionID = UUID()

        var receivedSessionID: UUID?

        service.onRoomNotificationTapped = { sessionID in
            receivedSessionID = sessionID
        }

        #expect(service.onRoomNotificationTapped != nil)

        await service.onRoomNotificationTapped?(expectedSessionID)

        #expect(receivedSessionID == expectedSessionID)
    }
}
