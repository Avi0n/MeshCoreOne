import Foundation
import Testing
import MeshCore
@testable import MC1Services

@Suite("NotificationActionHandler Tests")
struct NotificationActionHandlerTests {

    /// Minimal provider for channel-name fallback selection tests.
    private struct MockStringProvider: NotificationStringProvider {
        func discoveryNotificationTitle(for type: ContactType) -> String { "Mock Title" }
        var replyActionTitle: String { "Mock Reply" }
        var sendButtonTitle: String { "Mock Send" }
        var messagePlaceholder: String { "Mock Placeholder" }
        var markAsReadActionTitle: String { "Mock Mark as Read" }
        var lowBatteryTitle: String { "Mock Low Battery" }
        func lowBatteryBody(deviceName: String, percentage: Int) -> String { "Mock Battery" }
        var quickReplyFailedTitle: String { "Mock Not Sent" }
        func quickReplyFailedBody(conversationName: String) -> String { "Mock Failed" }
        var unknownContactName: String { "Mock Unknown" }

        func defaultChannelName(index: Int) -> String {
            "Localized Channel \(index)"
        }

        func reactionNotificationBody(emoji: String, messagePreview: String) -> String {
            "Mock reacted \(emoji) to \(messagePreview)"
        }
    }

    @MainActor
    private func makeHandler() async throws -> NotificationActionHandler {
        let session = MeshCoreSession(transport: SimulatorMockTransport())
        let services = try await ServiceContainer.forTesting(session: session)
        return services.notificationActionHandler
    }

    // MARK: - Reaction Preview Truncation

    @Test("Text at 49 characters is returned unchanged")
    func previewBelowLimitUnchanged() {
        let text = String(repeating: "a", count: 49)
        #expect(NotificationActionHandler.reactionPreview(for: text) == text)
    }

    @Test("Text at exactly 50 characters is returned unchanged")
    func previewAtLimitUnchanged() {
        let text = String(repeating: "b", count: 50)
        #expect(NotificationActionHandler.reactionPreview(for: text) == text)
    }

    @Test("Text at 51 characters is truncated to 47 plus ellipsis")
    func previewOverLimitTruncated() {
        let text = String(repeating: "c", count: 51)
        let expected = String(repeating: "c", count: 47) + "..."
        #expect(NotificationActionHandler.reactionPreview(for: text) == expected)
    }

    // MARK: - Channel Display Name Fallback

    @Test("Stored channel name wins over the localized fallback")
    @MainActor
    func channelDisplayNamePrefersStoredName() async throws {
        let handler = try await makeHandler()
        #expect(handler.channelDisplayName(name: "Rescue Net", index: 2) == "Rescue Net")
    }

    @Test("Missing name falls back to the string provider")
    @MainActor
    func channelDisplayNameUsesProvider() async throws {
        let session = MeshCoreSession(transport: SimulatorMockTransport())
        let services = try await ServiceContainer.forTesting(session: session)
        services.notificationService.setStringProvider(MockStringProvider())
        let handler = services.notificationActionHandler
        #expect(handler.channelDisplayName(name: nil, index: 3) == "Localized Channel 3")
    }

    @Test("Missing name and provider fall back to the English literal")
    @MainActor
    func channelDisplayNameLastResortLiteral() async throws {
        let handler = try await makeHandler()
        #expect(handler.channelDisplayName(name: nil, index: 7) == "Channel 7")
    }
}
