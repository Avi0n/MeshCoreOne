// NotificationServiceTests.swift
import Foundation
@testable import MC1Services
import Testing

@Suite("NotificationService Tests")
struct NotificationServiceTests {
  @Test
  @MainActor
  func `Suppression flag defaults to false`() {
    let service = NotificationService()
    #expect(service.isSuppressingNotifications == false)
  }

  @Test
  @MainActor
  func `Suppression flag can be set and cleared`() {
    let service = NotificationService()

    service.isSuppressingNotifications = true
    #expect(service.isSuppressingNotifications == true)

    service.isSuppressingNotifications = false
    #expect(service.isSuppressingNotifications == false)
  }

  @Test
  @MainActor
  func `Suppression flag can be toggled multiple times`() {
    let service = NotificationService()

    // Toggle several times
    service.isSuppressingNotifications = true
    service.isSuppressingNotifications = false
    service.isSuppressingNotifications = true
    service.isSuppressingNotifications = true // Setting same value
    service.isSuppressingNotifications = false

    #expect(service.isSuppressingNotifications == false)
  }

  // MARK: - Reaction Notification Tests

  @Test
  @MainActor
  func `onReactionNotificationTapped callback can be set`() async {
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

  @Test
  @MainActor
  func `onReactionNotificationTapped receives all parameters`() async {
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

  @Test
  @MainActor
  func `Room message notification is suppressed when isSuppressingNotifications is true`() async {
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

  @Test
  func `Notification category includes reaction`() {
    // Verify reaction category exists in the enum
    let category = NotificationCategory.reaction
    #expect(category.rawValue == "REACTION")
  }

  // MARK: - Room Notification Tests

  @Test
  @MainActor
  func `Active room session tracking can be set and cleared`() {
    let service = NotificationService()
    let sessionID = UUID()

    #expect(service.activeRoomSessionID == nil)

    service.activeRoomSessionID = sessionID
    #expect(service.activeRoomSessionID == sessionID)

    service.activeRoomSessionID = nil
    #expect(service.activeRoomSessionID == nil)
  }

  @Test
  @MainActor
  func `setActiveConversation populates only the passed slot and clears the rest`() {
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

  @Test
  @MainActor
  func `onRoomMarkAsRead callback can be set and receives parameters`() async {
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

  @Test
  @MainActor
  func `onRoomNotificationTapped callback can be set and receives the session ID`() async {
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
