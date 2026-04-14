import Testing
import Foundation
import MC1Services
@testable import MC1

@Suite("Navigation Coordinator Notification Handler Tests")
@MainActor
struct NavigationCoordinatorNotificationTests {

    // MARK: - Test Helpers

    private static func makeContact(
        id: UUID = UUID(),
        radioID: UUID = UUID(),
        name: String = "TestContact"
    ) -> ContactDTO {
        ContactDTO(
            id: id,
            radioID: radioID,
            publicKey: Data(repeating: 0xAA, count: 32),
            name: name,
            typeRawValue: 0x01,
            flags: 0,
            outPathLength: 0,
            outPath: Data(),
            lastAdvertTimestamp: 0,
            latitude: 0,
            longitude: 0,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0,
            unreadMentionCount: 0,
            ocvPreset: nil,
            customOCVArrayString: nil
        )
    }

    private static func makeChannel(
        id: UUID = UUID(),
        radioID: UUID = UUID(),
        name: String = "TestChannel",
        index: UInt8 = 0
    ) -> ChannelDTO {
        ChannelDTO(
            id: id,
            radioID: radioID,
            index: index,
            name: name,
            secret: Data(),
            isEnabled: true,
            lastMessageDate: nil,
            unreadCount: 0,
            unreadMentionCount: 0,
            notificationLevel: .all,
            isFavorite: false
        )
    }

    private static func makeDeviceDTO(manualAddContacts: Bool = false) -> DeviceDTO {
        DeviceDTO(
            id: UUID(),
            publicKey: Data(repeating: 0xBB, count: 32),
            nodeName: "TestNode",
            firmwareVersion: 1,
            firmwareVersionString: "1.12.0",
            manufacturerName: "Test",
            buildDate: "2025-01-01",
            maxContacts: 100,
            maxChannels: 8,
            frequency: 915_000,
            bandwidth: 250_000,
            spreadingFactor: 10,
            codingRate: 5,
            txPower: 20,
            maxTxPower: 20,
            latitude: 0,
            longitude: 0,
            blePin: 0,
            manualAddContacts: manualAddContacts,
            multiAcks: 2,
            telemetryModeBase: 2,
            telemetryModeLoc: 0,
            telemetryModeEnv: 0,
            advertLocationPolicy: 0,
            lastConnected: Date(),
            lastContactSync: 0,
            isActive: true,
            ocvPreset: nil,
            customOCVArrayString: nil
        )
    }

    /// Creates an in-memory data store seeded with a contact and channel.
    private static func makeSeededDataStore(
        contact: ContactDTO,
        channel: ChannelDTO
    ) async throws -> PersistenceStore {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        try await dataStore.saveContact(contact)
        try await dataStore.saveChannel(channel)
        return dataStore
    }

    // MARK: - DM Notification Tap

    @Test("DM notification tap navigates to chat with contact")
    func dmNotificationTapNavigatesToChat() async throws {
        let contact = Self.makeContact()
        let dataStore = try await Self.makeSeededDataStore(
            contact: contact,
            channel: Self.makeChannel()
        )
        let coordinator = NavigationCoordinator()
        let notificationService = NotificationService()

        coordinator.configureNotificationHandlers(
            notificationService: notificationService,
            dataStore: dataStore,
            connectedDevice: { nil }
        )

        // Invoke the handler directly
        await notificationService.onNotificationTapped?(contact.id)

        #expect(coordinator.pendingChatContact?.id == contact.id)
        #expect(coordinator.chatsSelectedRoute == .direct(contact))
        #expect(coordinator.selectedTab == 0)
    }

    // MARK: - New Contact Notification Tap

    @Test("New contact notification with manualAddContacts navigates to discovery")
    func newContactManualAddNavigatesToDiscovery() async throws {
        let contact = Self.makeContact()
        let dataStore = try await Self.makeSeededDataStore(
            contact: contact,
            channel: Self.makeChannel()
        )
        let coordinator = NavigationCoordinator()
        let notificationService = NotificationService()
        let device = Self.makeDeviceDTO(manualAddContacts: true)

        coordinator.configureNotificationHandlers(
            notificationService: notificationService,
            dataStore: dataStore,
            connectedDevice: { device }
        )

        await notificationService.onNewContactNotificationTapped?(contact.id)

        #expect(coordinator.pendingDiscoveryNavigation == true)
        #expect(coordinator.selectedTab == 1)
    }

    @Test("New contact notification without manualAddContacts navigates to contact detail")
    func newContactAutoAddNavigatesToContactDetail() async throws {
        let contact = Self.makeContact()
        let dataStore = try await Self.makeSeededDataStore(
            contact: contact,
            channel: Self.makeChannel()
        )
        let coordinator = NavigationCoordinator()
        let notificationService = NotificationService()
        let device = Self.makeDeviceDTO(manualAddContacts: false)

        coordinator.configureNotificationHandlers(
            notificationService: notificationService,
            dataStore: dataStore,
            connectedDevice: { device }
        )

        await notificationService.onNewContactNotificationTapped?(contact.id)

        #expect(coordinator.pendingContactDetail?.id == contact.id)
        #expect(coordinator.selectedTab == 1)
    }

    // MARK: - Channel Notification Tap

    @Test("Channel notification tap navigates to channel")
    func channelNotificationTapNavigatesToChannel() async throws {
        let radioID = UUID()
        let channelIndex: UInt8 = 3
        let channel = Self.makeChannel(radioID: radioID, index: channelIndex)
        let dataStore = try await Self.makeSeededDataStore(
            contact: Self.makeContact(),
            channel: channel
        )
        let coordinator = NavigationCoordinator()
        let notificationService = NotificationService()

        coordinator.configureNotificationHandlers(
            notificationService: notificationService,
            dataStore: dataStore,
            connectedDevice: { nil }
        )

        await notificationService.onChannelNotificationTapped?(radioID, channelIndex)

        #expect(coordinator.pendingChannel?.id == channel.id)
        #expect(coordinator.chatsSelectedRoute == .channel(channel))
        #expect(coordinator.selectedTab == 0)
    }

    // MARK: - Reaction Notification Tap

    @Test("Reaction notification on DM navigates to chat with scrollToMessageID")
    func reactionOnDMNavigatesToChatWithScroll() async throws {
        let contact = Self.makeContact()
        let messageID = UUID()
        let dataStore = try await Self.makeSeededDataStore(
            contact: contact,
            channel: Self.makeChannel()
        )
        let coordinator = NavigationCoordinator()
        let notificationService = NotificationService()

        coordinator.configureNotificationHandlers(
            notificationService: notificationService,
            dataStore: dataStore,
            connectedDevice: { nil }
        )

        await notificationService.onReactionNotificationTapped?(contact.id, nil, nil, messageID)

        #expect(coordinator.pendingChatContact?.id == contact.id)
        #expect(coordinator.pendingScrollToMessageID == messageID)
        #expect(coordinator.selectedTab == 0)
    }

    @Test("Reaction notification on channel navigates to channel with scrollToMessageID")
    func reactionOnChannelNavigatesToChannelWithScroll() async throws {
        let radioID = UUID()
        let channelIndex: UInt8 = 1
        let channel = Self.makeChannel(radioID: radioID, index: channelIndex)
        let messageID = UUID()
        let dataStore = try await Self.makeSeededDataStore(
            contact: Self.makeContact(),
            channel: channel
        )
        let coordinator = NavigationCoordinator()
        let notificationService = NotificationService()

        coordinator.configureNotificationHandlers(
            notificationService: notificationService,
            dataStore: dataStore,
            connectedDevice: { nil }
        )

        // contactID is nil → falls through to channel branch
        await notificationService.onReactionNotificationTapped?(nil, channelIndex, radioID, messageID)

        #expect(coordinator.pendingChannel?.id == channel.id)
        #expect(coordinator.pendingScrollToMessageID == messageID)
        #expect(coordinator.selectedTab == 0)
    }
}
