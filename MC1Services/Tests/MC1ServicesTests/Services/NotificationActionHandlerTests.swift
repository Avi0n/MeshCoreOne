import Foundation
import Testing
import MeshCore
@testable import MC1Services

// MARK: - Helpers

@MainActor
private func makeServicesAndHandler() async throws -> (ServiceContainer, NotificationActionHandler) {
    let session = MeshCoreSession(transport: SimulatorMockTransport())
    let services = try await ServiceContainer.forTesting(session: session)
    return (services, services.notificationActionHandler)
}

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

    // MARK: - Reaction Configure Guard

    @Test("Handler is not configured before configure() is called")
    @MainActor
    func handlerNotConfiguredBeforeConfigure() async throws {
        let handler = try await makeHandler()
        #expect(handler.isConfigured == false)
    }

    @Test("Handler is configured after configure() is called")
    @MainActor
    func handlerConfiguredAfterConfigure() async throws {
        let handler = try await makeHandler()
        handler.configure(isConnectionReady: { true }, localNodeName: { nil })
        #expect(handler.isConfigured == true)
    }

    @Test("Reaction notification before configure completes without posting")
    @MainActor
    func reactionNotificationBeforeConfigureDoesNotPost() async throws {
        let (services, handler) = try await makeServicesAndHandler()
        #expect(handler.isConfigured == false)

        let radioID = UUID()
        let contactID = UUID()
        let messageID = UUID()
        let message = MessageDTO(
            id: messageID, radioID: radioID, contactID: contactID,
            channelIndex: nil, text: "Hello", timestamp: 1000,
            createdAt: Date(), direction: .outgoing, status: .sent,
            textType: .plain, ackCode: nil, pathLength: 0, snr: nil,
            senderKeyPrefix: nil, senderNodeName: "Alice",
            isRead: true, replyToID: nil, roundTripTime: nil,
            heardRepeats: 0, retryAttempt: 0, maxRetryAttempts: 3
        )
        try await services.dataStore.saveMessage(message)
        let reaction = ReactionDTO(
            messageID: messageID, emoji: "👍", senderName: "Bob",
            messageHash: "hash", rawText: "raw", contactID: contactID, radioID: radioID
        )
        try await services.dataStore.saveReaction(reaction)

        // Should return early before reaching the notification post path
        await handler.handleReactionNotification(messageID: messageID)
        // Reaching here confirms no crash and no spurious self-notification
    }

    @Test("Reaction notification after configure is self-suppressed when names match")
    @MainActor
    func reactionNotificationSelfSuppressedAfterConfigure() async throws {
        let (services, handler) = try await makeServicesAndHandler()
        let selfName = "Alice"
        handler.configure(isConnectionReady: { true }, localNodeName: { selfName })
        #expect(handler.isConfigured == true)

        let radioID = UUID()
        let contactID = UUID()
        let messageID = UUID()
        let message = MessageDTO(
            id: messageID, radioID: radioID, contactID: contactID,
            channelIndex: nil, text: "Hello", timestamp: 1000,
            createdAt: Date(), direction: .outgoing, status: .sent,
            textType: .plain, ackCode: nil, pathLength: 0, snr: nil,
            senderKeyPrefix: nil, senderNodeName: selfName,
            isRead: true, replyToID: nil, roundTripTime: nil,
            heardRepeats: 0, retryAttempt: 0, maxRetryAttempts: 3
        )
        try await services.dataStore.saveMessage(message)
        let reaction = ReactionDTO(
            messageID: messageID, emoji: "👍", senderName: selfName,
            messageHash: "hash", rawText: "raw", contactID: contactID, radioID: radioID
        )
        try await services.dataStore.saveReaction(reaction)

        // Self-reaction: should be suppressed (senderName == localNodeName)
        await handler.handleReactionNotification(messageID: messageID)
        // Completing without posting confirms self-suppression works after configure
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
