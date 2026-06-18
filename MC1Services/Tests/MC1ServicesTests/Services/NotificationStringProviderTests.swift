import Foundation
import Testing
@testable import MC1Services

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

        var replyActionTitle: String { "Mock Reply" }
        var sendButtonTitle: String { "Mock Send" }
        var messagePlaceholder: String { "Mock Placeholder" }
        var markAsReadActionTitle: String { "Mock Mark as Read" }
        var lowBatteryTitle: String { "Mock Low Battery" }

        func lowBatteryBody(deviceName: String, percentage: Int) -> String {
            "Mock \(deviceName) at \(percentage)%"
        }

        var quickReplyFailedTitle: String { "Mock Not Sent" }

        func quickReplyFailedBody(conversationName: String) -> String {
            "Mock reply to \(conversationName) failed"
        }

        var unknownContactName: String { "Mock Unknown Contact" }

        func defaultChannelName(index: Int) -> String {
            "Mock Channel \(index)"
        }

        func reactionNotificationBody(emoji: String, messagePreview: String) -> String {
            "Mock reacted \(emoji) to \(messagePreview)"
        }
    }

    @Test("Provider returns correct title for chat type")
    func providerReturnsChatTitle() {
        let provider = MockStringProvider()
        let title = provider.discoveryNotificationTitle(for: .chat)
        #expect(title == "Mock Contact Title")
    }

    @Test("Provider returns correct title for repeater type")
    func providerReturnsRepeaterTitle() {
        let provider = MockStringProvider()
        let title = provider.discoveryNotificationTitle(for: .repeater)
        #expect(title == "Mock Repeater Title")
    }

    @Test("Provider returns correct title for room type")
    func providerReturnsRoomTitle() {
        let provider = MockStringProvider()
        let title = provider.discoveryNotificationTitle(for: .room)
        #expect(title == "Mock Room Title")
    }

    @Test("Provider returns correct low battery title")
    func providerReturnsLowBatteryTitle() {
        let provider = MockStringProvider()
        #expect(provider.lowBatteryTitle == "Mock Low Battery")
    }

    @Test("Provider returns correct low battery body with device name and percentage")
    func providerReturnsLowBatteryBody() {
        let provider = MockStringProvider()
        let body = provider.lowBatteryBody(deviceName: "Node-7", percentage: 15)
        #expect(body == "Mock Node-7 at 15%")
    }

    @Test("Default fallback titles are English")
    @MainActor
    func defaultFallbackTitlesAreEnglish() {
        let service = NotificationService()
        #expect(service.defaultDiscoveryTitle(for: .chat) == "New Contact Discovered")
        #expect(service.defaultDiscoveryTitle(for: .repeater) == "New Repeater Discovered")
        #expect(service.defaultDiscoveryTitle(for: .room) == "New Room Discovered")
    }
}
