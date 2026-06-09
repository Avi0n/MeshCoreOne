import Testing
import Foundation
import MeshCore
@testable import MC1Services

@Suite("ServiceContainer Wiring Tests")
struct ServiceContainerWiringTests {

    /// Creates a wired ServiceContainer using the test factory.
    @MainActor
    private func makeWiredContainer() async throws -> ServiceContainer {
        let transport = SimulatorMockTransport()
        let session = MeshCoreSession(transport: transport)
        return try await ServiceContainer.forTesting(session: session)
    }

    /// Creates an unwired ServiceContainer for pre-wiring assertions.
    @MainActor
    private func makeUnwiredContainer() async throws -> ServiceContainer {
        let transport = SimulatorMockTransport()
        let session = MeshCoreSession(transport: transport)
        return try await ServiceContainer.forTesting(session: session, wired: false)
    }

    @Test("wireServices establishes all 6 cross-service connections")
    @MainActor
    func wireServicesEstablishesAllConnections() async throws {
        let container = try await makeWiredContainer()

        // 1. messageService → contactService
        let hasContact = await container.messageService.hasContactServiceWired
        #expect(hasContact, "messageService should have contactService wired")

        // 2. contactService → syncCoordinator
        let hasContactSync = await container.contactService.hasSyncCoordinatorWired
        #expect(hasContactSync, "contactService should have syncCoordinator wired")

        // 3. nodeConfigService → syncCoordinator
        let hasNodeSync = await container.nodeConfigService.hasSyncCoordinatorWired
        #expect(hasNodeSync, "nodeConfigService should have syncCoordinator wired")

        // 4. contactService cleanup handler
        let hasCleanup = await container.contactService.hasCleanupHandlerWired
        #expect(hasCleanup, "contactService should have cleanupHandler wired")

        // 5. channelService channel update handler
        let hasChannelUpdate = await container.channelService.hasChannelUpdateHandlerWired
        #expect(hasChannelUpdate, "channelService should have channelUpdateHandler wired")

        // 6. rxLogService → heardRepeatsService
        let hasHeardRepeats = await container.rxLogService.hasHeardRepeatsServiceWired
        #expect(hasHeardRepeats, "rxLogService should have heardRepeatsService wired")
    }

    @Test("wireServices is idempotent")
    @MainActor
    func wireServicesIsIdempotent() async throws {
        let container = try await makeWiredContainer()

        // Call wireServices again on an already-wired container
        await container.wireServices()

        let hasContact = await container.messageService.hasContactServiceWired
        #expect(hasContact, "connections should persist after duplicate wireServices call")
    }

    @Test("forTesting with wired false does not wire services")
    @MainActor
    func forTestingUnwiredDoesNotWire() async throws {
        let container = try await makeUnwiredContainer()

        let hasContact = await container.messageService.hasContactServiceWired
        #expect(!hasContact, "messageService should not have contactService before wiring")

        let hasCleanup = await container.contactService.hasCleanupHandlerWired
        #expect(!hasCleanup, "contactService should not have cleanupHandler before wiring")

        let hasHeardRepeats = await container.rxLogService.hasHeardRepeatsServiceWired
        #expect(!hasHeardRepeats, "rxLogService should not have heardRepeatsService before wiring")
    }

    @Test("startEventMonitoring activates ACK expiry checker; stopEventMonitoring deactivates it")
    @MainActor
    func startEventMonitoringActivatesAckChecking() async throws {
        let container = try await makeWiredContainer()
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
