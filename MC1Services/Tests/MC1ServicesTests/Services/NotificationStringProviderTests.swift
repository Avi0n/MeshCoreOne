import Foundation
@testable import MC1Services
import Testing

struct NotificationStringProviderTests {
  /// Mock implementation for testing
  struct MockStringProvider: NotificationStringProvider {
    func discoveryNotificationTitle(for type: ContactType) -> String {
      switch type {
      case .chat: "Mock Contact Title"
      case .repeater: "Mock Repeater Title"
      case .room: "Mock Room Title"
      }
    }

    var replyActionTitle: String {
      "Mock Reply"
    }

    var sendButtonTitle: String {
      "Mock Send"
    }

    var messagePlaceholder: String {
      "Mock Placeholder"
    }

    var markAsReadActionTitle: String {
      "Mock Mark as Read"
    }

    var lowBatteryTitle: String {
      "Mock Low Battery"
    }

    func lowBatteryBody(deviceName: String, percentage: Int) -> String {
      "Mock \(deviceName) at \(percentage)%"
    }

    var quickReplyFailedTitle: String {
      "Mock Not Sent"
    }

    func quickReplyFailedBody(conversationName: String) -> String {
      "Mock reply to \(conversationName) failed"
    }

    var unknownContactName: String {
      "Mock Unknown Contact"
    }

    func defaultChannelName(index: Int) -> String {
      "Mock Channel \(index)"
    }

    func reactionNotificationBody(emoji: String, messagePreview: String) -> String {
      "Mock reacted \(emoji) to \(messagePreview)"
    }
  }

  @Test
  func `Provider returns correct title for chat type`() {
    let provider = MockStringProvider()
    let title = provider.discoveryNotificationTitle(for: .chat)
    #expect(title == "Mock Contact Title")
  }

  @Test
  func `Provider returns correct title for repeater type`() {
    let provider = MockStringProvider()
    let title = provider.discoveryNotificationTitle(for: .repeater)
    #expect(title == "Mock Repeater Title")
  }

  @Test
  func `Provider returns correct title for room type`() {
    let provider = MockStringProvider()
    let title = provider.discoveryNotificationTitle(for: .room)
    #expect(title == "Mock Room Title")
  }

  @Test
  func `Provider returns correct low battery title`() {
    let provider = MockStringProvider()
    #expect(provider.lowBatteryTitle == "Mock Low Battery")
  }

  @Test
  func `Provider returns correct low battery body with device name and percentage`() {
    let provider = MockStringProvider()
    let body = provider.lowBatteryBody(deviceName: "Node-7", percentage: 15)
    #expect(body == "Mock Node-7 at 15%")
  }

  @Test
  @MainActor
  func `Default fallback titles are English`() {
    let service = NotificationService()
    #expect(service.defaultDiscoveryTitle(for: .chat) == "New Contact Discovered")
    #expect(service.defaultDiscoveryTitle(for: .repeater) == "New Repeater Discovered")
    #expect(service.defaultDiscoveryTitle(for: .room) == "New Room Discovered")
  }
}
