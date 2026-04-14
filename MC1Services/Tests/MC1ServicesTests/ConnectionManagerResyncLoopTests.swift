import Foundation
import Testing
import MeshCore
import MeshCoreTestSupport
@testable import MC1Services

@Suite("ConnectionManager Resync Loop Tests")
@MainActor
struct ConnectionManagerResyncLoopTests {

    /// Creates a manager + services where performResync fails immediately
    /// (transport not connected → syncContacts throws .notConnected).
    private func makeResyncTestHarness(deviceID: UUID) async throws -> (
        manager: ConnectionManager,
        services: ServiceContainer,
        session: MeshCoreSession
    ) {
        let (manager, _) = try ConnectionManager.createForTesting()
        let transport = SimulatorMockTransport()
        // Don't connect transport — commands fail immediately with .notConnected
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.5, clientIdentifier: "ResyncTest")
        )
        let services = try await ServiceContainer.forTesting(session: session, wired: false)

        // Insert a device so fetchDevice doesn't fail inside performFullSync
        let device = DeviceDTO.testDevice(id: deviceID)
        try await services.dataStore.saveDevice(device)

        // Set manager to .syncing with .wantsConnection — the resync loop's guard requires .isOperational
        manager.setTestState(
            connectionState: .syncing,
            services: services,
            session: session,
            connectedDevice: DeviceDTO.testDevice(id: deviceID),
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )

        return (manager, services, session)
    }

    // MARK: - Cancellation

    @Test("Cancelling resync loop closes the activity bracket with succeeded=false")
    func cancellationClosesBracket() async throws {
        let deviceID = UUID()
        let (manager, services, _) = try await makeResyncTestHarness(deviceID: deviceID)

        let startedTracker = CallTracker()
        let succeededValues = ValueTracker<Bool>()

        await services.syncCoordinator.setSyncActivityCallbacks(
            onStarted: { startedTracker.markCalled() },
            onEnded: { succeeded in succeededValues.record(succeeded) },
            onPhaseChanged: { _ in }
        )

        manager.startResyncLoop(radioID: deviceID, services: services)

        // Wait for the outer bracket to open
        try await waitUntil("beginResyncActivity should fire") {
            startedTracker.wasCalled
        }

        // Cancel the loop
        manager.cancelResyncLoop()

        // Wait for the catch-all to close the bracket
        try await waitUntil("endResyncActivity should fire after cancellation") {
            !succeededValues.values.isEmpty
        }

        // The catch-all should report failure
        let lastSucceeded = succeededValues.values.last
        #expect(lastSucceeded == false, "Cancelled resync should report succeeded=false")
    }

    // MARK: - Guard Exit

    @Test("Resync loop exits and closes bracket when connectionIntent changes")
    func guardExitClosesBracket() async throws {
        let deviceID = UUID()
        let (manager, services, _) = try await makeResyncTestHarness(deviceID: deviceID)

        let startedTracker = CallTracker()
        let succeededValues = ValueTracker<Bool>()

        await services.syncCoordinator.setSyncActivityCallbacks(
            onStarted: { startedTracker.markCalled() },
            onEnded: { succeeded in succeededValues.record(succeeded) },
            onPhaseChanged: { _ in }
        )

        manager.startResyncLoop(radioID: deviceID, services: services)

        // Wait for the outer bracket to open
        try await waitUntil("beginResyncActivity should fire") {
            startedTracker.wasCalled
        }

        // Change intent while the loop is sleeping between retries.
        // The MainActor is free during Task.sleep, so this runs immediately.
        manager.setTestState(connectionIntent: .userDisconnected)

        // The guard at the top of the while loop will fail after the sleep,
        // causing a break → catch-all fires endResyncActivity(succeeded: false).
        try await waitUntil(timeout: .seconds(5), "endResyncActivity should fire after guard exit") {
            !succeededValues.values.isEmpty
        }

        let lastSucceeded = succeededValues.values.last
        #expect(lastSucceeded == false, "Guard exit should report succeeded=false")
    }

    // MARK: - Max Attempts

    @Test("Max resync attempts triggers disconnect and onResyncFailed",
          .timeLimit(.minutes(1)))
    func maxAttemptsTriggersDisconnect() async throws {
        let deviceID = UUID()
        let (manager, services, _) = try await makeResyncTestHarness(deviceID: deviceID)

        let resyncFailedTracker = CallTracker()
        manager.onResyncFailed = {
            resyncFailedTracker.markCalled()
        }

        let succeededValues = ValueTracker<Bool>()

        await services.syncCoordinator.setSyncActivityCallbacks(
            onStarted: { },
            onEnded: { succeeded in succeededValues.record(succeeded) },
            onPhaseChanged: { _ in }
        )

        manager.startResyncLoop(radioID: deviceID, services: services)

        // Wait for max attempts to be exhausted (3 attempts × 2s intervals)
        try await waitUntil(timeout: .seconds(15), "onResyncFailed should fire after max attempts") {
            resyncFailedTracker.wasCalled
        }

        // Manager should have disconnected
        #expect(manager.connectionState == .disconnected)

        // The endResyncActivity(succeeded: false) should have been called
        // (once for each inner performFullSync failure + once for the outer bracket)
        let finalValues = succeededValues.values
        #expect(finalValues.last == false, "Max-attempts bracket should close with succeeded=false")
        #expect(!finalValues.contains(true), "No resync iteration should report success")
    }

    // MARK: - Bracket Counting

    @Test("Each resync attempt opens and closes an inner bracket",
          .timeLimit(.minutes(1)))
    func innerBracketPerAttempt() async throws {
        let deviceID = UUID()
        let (manager, services, _) = try await makeResyncTestHarness(deviceID: deviceID)

        let startCount = CallTracker()
        let endCount = CallTracker()

        await services.syncCoordinator.setSyncActivityCallbacks(
            onStarted: { startCount.markCalled() },
            onEnded: { _ in endCount.markCalled() },
            onPhaseChanged: { _ in }
        )

        let resyncFailedTracker = CallTracker()
        manager.onResyncFailed = { resyncFailedTracker.markCalled() }

        manager.startResyncLoop(radioID: deviceID, services: services)

        // Wait for loop to exhaust
        try await waitUntil(timeout: .seconds(15), "resync loop should exhaust") {
            resyncFailedTracker.wasCalled
        }

        // 1 outer start + 3 inner starts = 4
        #expect(startCount.callCount == 4, "Expected 1 outer + 3 inner activity starts, got \(startCount.callCount)")
        // 3 inner ends + 1 outer end = 4
        #expect(endCount.callCount == 4, "Expected 3 inner + 1 outer activity ends, got \(endCount.callCount)")
    }

    // MARK: - Resync Success Promotion

    @Test("Resync success promotes .syncing → .ready",
          .timeLimit(.minutes(1)))
    func resyncSuccessPromotesToReady() async throws {
        let deviceID = UUID()
        let (manager, services, _) = try await makeResyncTestHarness(deviceID: deviceID)

        // Override performResync to succeed
        await services.syncCoordinator.setPerformResyncOverride { _, _ in true }

        let succeededValues = ValueTracker<Bool>()
        await services.syncCoordinator.setSyncActivityCallbacks(
            onStarted: { },
            onEnded: { succeeded in succeededValues.record(succeeded) },
            onPhaseChanged: { _ in }
        )

        manager.startResyncLoop(radioID: deviceID, services: services)

        try await waitUntil(timeout: .seconds(10), "endResyncActivity should fire with success") {
            succeededValues.values.contains(true)
        }

        #expect(manager.connectionState == .ready)
    }

    @Test("Resync success calls onDeviceSynced",
          .timeLimit(.minutes(1)))
    func resyncSuccessCallsOnDeviceSynced() async throws {
        let deviceID = UUID()
        let (manager, services, _) = try await makeResyncTestHarness(deviceID: deviceID)

        // Override performResync to succeed
        await services.syncCoordinator.setPerformResyncOverride { _, _ in true }

        let onDeviceSyncedTracker = CallTracker()
        manager.onDeviceSynced = { onDeviceSyncedTracker.markCalled() }

        let succeededValues = ValueTracker<Bool>()
        await services.syncCoordinator.setSyncActivityCallbacks(
            onStarted: { },
            onEnded: { succeeded in succeededValues.record(succeeded) },
            onPhaseChanged: { _ in }
        )

        manager.startResyncLoop(radioID: deviceID, services: services)

        try await waitUntil(timeout: .seconds(10), "onDeviceSynced should fire after resync success") {
            onDeviceSyncedTracker.wasCalled
        }

        #expect(onDeviceSyncedTracker.wasCalled)
    }

    // MARK: - Disconnect During Syncing

    @Test("Disconnect during .syncing transitions to .disconnected and closes bracket",
          .timeLimit(.minutes(1)))
    func disconnectDuringSyncingClosesBracket() async throws {
        let deviceID = UUID()
        let (manager, services, _) = try await makeResyncTestHarness(deviceID: deviceID)

        let startedTracker = CallTracker()
        let succeededValues = ValueTracker<Bool>()

        await services.syncCoordinator.setSyncActivityCallbacks(
            onStarted: { startedTracker.markCalled() },
            onEnded: { succeeded in succeededValues.record(succeeded) },
            onPhaseChanged: { _ in }
        )

        manager.startResyncLoop(radioID: deviceID, services: services)

        // Wait for the outer bracket to open
        try await waitUntil("beginResyncActivity should fire") {
            startedTracker.wasCalled
        }

        // Disconnect while in .syncing
        await manager.disconnect(reason: .userInitiated)

        // Wait for bracket to close
        try await waitUntil("endResyncActivity should fire after disconnect") {
            !succeededValues.values.isEmpty
        }

        #expect(manager.connectionState == .disconnected)
        #expect(succeededValues.values.last == false)
    }
}
