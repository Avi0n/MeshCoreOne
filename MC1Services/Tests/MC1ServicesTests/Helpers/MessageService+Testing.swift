import Foundation
import MeshCoreTestSupport
import Testing
@testable import MC1Services
@testable import MeshCore

extension MessageService {
    static func createForTesting(
        defaultTimeout: TimeInterval = 5.0,
        connectTransport: Bool = false
    ) async throws -> (MessageService, PersistenceStore) {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        let transport = SimulatorMockTransport()
        if connectTransport {
            try await transport.connect()
        }
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: defaultTimeout)
        )
        let service = MessageService(session: session, dataStore: dataStore)
        return (service, dataStore)
    }

    func insertInFlightRetryForTest(_ messageID: UUID) {
        inFlightRetries.insert(messageID)
    }

    func setPendingAckForTest(_ tracking: PendingAck) {
        pendingAcks[tracking.messageID] = tracking
    }

    func setMessageFailedHandlerForTest(_ handler: @escaping @Sendable (UUID) async -> Void) {
        messageFailedHandler = handler
    }

    func setAckConfirmationHandlerForTest(_ handler: @escaping @Sendable (UUID, MessageStatus, UInt32?) async -> Void) {
        ackConfirmationHandler = handler
    }

    var sessionForTest: MeshCoreSession { session }

    func installSelfInfoForTest(publicKey: Data = Data(repeating: 0xAB, count: 32)) async {
        await session.installSelfInfoForTest(.testSelfInfo(publicKey: publicKey))
    }

    /// Waits until the session's dispatcher holds exactly `expectedCount`
    /// subscriptions, polling `subscriberCountForTest` without sleeping in
    /// the test itself.
    ///
    /// Required because `startEventMonitoring()` spawns a `Task` whose subscribe
    /// call races any subsequent `dispatchForTesting`. A one-hop `await` into the
    /// session actor is not sufficient: the listener Task must traverse
    /// session → dispatcher before the dispatch arrives.
    ///
    /// On restart, callers should first wait for `expectedCount: 0` to confirm
    /// the previous subscription has torn down (its `onTermination` cleanup
    /// runs on its own Task) before waiting for the new subscription.
    func waitForSubscriberCount(
        _ expectedCount: Int,
        timeout: Duration = .milliseconds(500)
    ) async {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        while ContinuousClock.now < deadline {
            if await sessionForTest.subscriberCountForTest == expectedCount {
                return
            }
            await Task.yield()
        }
        Issue.record("subscriber count did not reach \(expectedCount) within \(timeout)")
    }
}

extension SelfInfo {
    static func testSelfInfo(publicKey: Data = Data(repeating: 0xAB, count: 32)) -> SelfInfo {
        SelfInfo(
            advertisementType: 0,
            txPower: 20,
            maxTxPower: 20,
            publicKey: publicKey,
            latitude: 0,
            longitude: 0,
            multiAcks: 2,
            advertisementLocationPolicy: 0,
            telemetryModeEnvironment: 0,
            telemetryModeLocation: 0,
            telemetryModeBase: 2,
            manualAddContacts: false,
            radioFrequency: 915.0,
            radioBandwidth: 250.0,
            radioSpreadingFactor: 10,
            radioCodingRate: 5,
            name: "TestNode"
        )
    }
}

actor AckConfirmationTracker {
    var confirmedIDs: [UUID] = []
    func record(_ id: UUID) { confirmedIDs.append(id) }
}

actor MessageResentTracker {
    var resentIDs: [UUID] = []
    func record(_ id: UUID) { resentIDs.append(id) }
}

actor FailedMessageTracker {
    var failedIDs: [UUID] = []
    func record(_ id: UUID) { failedIDs.append(id) }
}

actor RetryStatusTracker {
    var updates: [(messageID: UUID, attempt: Int, maxAttempts: Int)] = []

    func record(messageID: UUID, attempt: Int, maxAttempts: Int) {
        updates.append((messageID, attempt, maxAttempts))
    }
}
