import Testing
import Foundation
import MeshCore
@testable import MC1Services

@Suite("ServiceContainer Wiring Tests")
struct ServiceContainerWiringTests {

    /// Creates a ServiceContainer using the test factory.
    @MainActor
    private func makeContainer() async throws -> ServiceContainer {
        let transport = SimulatorMockTransport()
        let session = MeshCoreSession(transport: transport)
        return try await ServiceContainer.forTesting(session: session)
    }

    @Test("init establishes all 6 cross-service connections")
    @MainActor
    func initEstablishesAllConnections() async throws {
        let container = try await makeContainer()

        // 1. messageService → contactService
        let hasContact = await container.messageService.hasContactServiceWired
        #expect(hasContact, "messageService should have contactService injected")

        // 2. contactService → syncCoordinator
        let hasContactSync = await container.contactService.hasSyncCoordinatorWired
        #expect(hasContactSync, "contactService should have syncCoordinator injected")

        // 3. nodeConfigService → syncCoordinator
        let hasNodeSync = await container.nodeConfigService.hasSyncCoordinatorWired
        #expect(hasNodeSync, "nodeConfigService should have syncCoordinator injected")

        // 4. contactService → cleanup coordinator
        let hasCleanup = await container.contactService.hasCleanupCoordinatorWired
        #expect(hasCleanup, "contactService should have cleanupCoordinator injected")

        // 5. channelService → rxLogService
        let hasRxLog = await container.channelService.hasRxLogServiceWired
        #expect(hasRxLog, "channelService should have rxLogService injected")

        // 6. rxLogService → heardRepeatsService
        let hasHeardRepeats = await container.rxLogService.hasHeardRepeatsServiceWired
        #expect(hasHeardRepeats, "rxLogService should have heardRepeatsService injected")
    }

    @Test("tearDown clears the wired message and discovery handlers")
    @MainActor
    func tearDownClearsWiredHandlers() async throws {
        let container = try await makeContainer()
        let radioID = UUID()
        try await container.dataStore.saveDevice(
            DeviceDTO.testDevice(id: radioID, radioID: radioID)
        )

        await container.syncCoordinator.wireMessageHandlers(dependencies: container.syncDependencies, radioID: radioID)
        await container.syncCoordinator.startDiscoveryEventMonitoring(dependencies: container.syncDependencies, radioID: radioID)

        #expect(await container.messagePollingService.hasMessageHandlersWired)
        #expect(container.advertisementService.eventBroadcaster.subscriberCount > 0,
                "startDiscoveryEventMonitoring must subscribe to advertisement events")

        await container.tearDown()

        #expect(await container.messagePollingService.hasMessageHandlersWired == false,
                "tearDown must clear message handlers to break the container retain cycle")
        #expect(container.advertisementService.eventBroadcaster.subscriberCount == 0,
                "tearDown must end every advertisement event subscription to break the container retain cycle")
    }

    @Test("tearDown clears the notification action forwarders that capture the handler")
    @MainActor
    func tearDownClearsNotificationActionForwarders() async throws {
        let container = try await makeContainer()
        let handler = container.notificationActionHandler

        // Mirror AppState's forwarders: each captures the handler strongly, so a
        // dropped nil-out leaves the notificationService <-> handler cycle alive.
        container.notificationService.onQuickReply = { contactID, text in
            await handler.handleQuickReply(contactID: contactID, text: text)
        }
        container.notificationService.onChannelQuickReply = { radioID, channelIndex, text in
            await handler.handleChannelQuickReply(radioID: radioID, channelIndex: channelIndex, text: text)
        }
        container.notificationService.onMarkAsRead = { contactID, messageID in
            await handler.handleMarkAsRead(contactID: contactID, messageID: messageID)
        }
        container.notificationService.onChannelMarkAsRead = { radioID, channelIndex, messageID in
            await handler.handleChannelMarkAsRead(radioID: radioID, channelIndex: channelIndex, messageID: messageID)
        }
        container.notificationService.onRoomMarkAsRead = { sessionID, messageID in
            await handler.handleRoomMarkAsRead(sessionID: sessionID, messageID: messageID)
        }

        await container.tearDown()

        #expect(container.notificationService.onQuickReply == nil)
        #expect(container.notificationService.onChannelQuickReply == nil)
        #expect(container.notificationService.onMarkAsRead == nil)
        #expect(container.notificationService.onChannelMarkAsRead == nil)
        #expect(container.notificationService.onRoomMarkAsRead == nil)
    }

    @Test("startEventMonitoring activates ACK expiry checker; stopEventMonitoring deactivates it")
    @MainActor
    func startEventMonitoringActivatesAckChecking() async throws {
        let container = try await makeContainer()
        let radioID = UUID()
        try await container.dataStore.saveDevice(
            DeviceDTO.testDevice(id: radioID, radioID: radioID)
        )

        #expect(await container.messageService.isAckExpiryCheckingActive == false)

        await container.startEventMonitoring(
            radioID: radioID,
            enableAutoFetch: false,
            enableAdvertisementMonitoring: false
        )
        #expect(await container.messageService.isAckExpiryCheckingActive == true)

        await container.stopEventMonitoring()
        #expect(await container.messageService.isAckExpiryCheckingActive == false)
    }
}
