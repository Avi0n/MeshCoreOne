import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("NotificationActionHandler Tests")
struct NotificationActionHandlerTests {
  /// Minimal provider for channel-name fallback selection tests.
  private struct MockStringProvider: NotificationStringProvider {
    func discoveryNotificationTitle(for type: ContactType) -> String {
      "Mock Title"
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
      "Mock Battery"
    }

    var quickReplyFailedTitle: String {
      "Mock Not Sent"
    }

    func quickReplyFailedBody(conversationName: String) -> String {
      "Mock Failed"
    }

    var unknownContactName: String {
      "Mock Unknown"
    }

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

  @Test
  @MainActor
  func `Handler is not configured before configure() is called`() async throws {
    let handler = try await makeHandler()
    #expect(handler.isConfigured == false)
  }

  @Test
  @MainActor
  func `Handler is configured after configure() is called`() async throws {
    let handler = try await makeHandler()
    handler.configure(isConnectionReady: { true }, localNodeName: { nil })
    #expect(handler.isConfigured == true)
  }

  // MARK: - Reaction Preview Truncation

  @Test
  func `Text at 49 characters is returned unchanged`() {
    let text = String(repeating: "a", count: 49)
    #expect(NotificationActionHandler.reactionPreview(for: text) == text)
  }

  @Test
  func `Text at exactly 50 characters is returned unchanged`() {
    let text = String(repeating: "b", count: 50)
    #expect(NotificationActionHandler.reactionPreview(for: text) == text)
  }

  @Test
  func `Text at 51 characters is truncated to 47 plus ellipsis`() {
    let text = String(repeating: "c", count: 51)
    let expected = String(repeating: "c", count: 47) + "..."
    #expect(NotificationActionHandler.reactionPreview(for: text) == expected)
  }

  // MARK: - Channel Display Name Fallback

  @Test
  @MainActor
  func `Stored channel name wins over the localized fallback`() async throws {
    let handler = try await makeHandler()
    #expect(handler.channelDisplayName(name: "Rescue Net", index: 2) == "Rescue Net")
  }

  @Test
  @MainActor
  func `Missing name falls back to the string provider`() async throws {
    let session = MeshCoreSession(transport: SimulatorMockTransport())
    let services = try await ServiceContainer.forTesting(session: session)
    services.notificationService.setStringProvider(MockStringProvider())
    let handler = services.notificationActionHandler
    #expect(handler.channelDisplayName(name: nil, index: 3) == "Localized Channel 3")
  }

  @Test
  @MainActor
  func `Missing name and provider fall back to the English literal`() async throws {
    let handler = try await makeHandler()
    #expect(handler.channelDisplayName(name: nil, index: 7) == "Channel 7")
  }
}
