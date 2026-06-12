import Foundation
import MeshCoreTestSupport
import Testing
@testable import MC1Services
@testable import MeshCore

extension MessageService {
    static func createForTesting(
        defaultTimeout: TimeInterval = 5.0,
        connectTransport: Bool = false,
        config: MessageServiceConfig = .default
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
        let service = MessageService(session: session, dataStore: dataStore, contactService: nil, config: config)
        return (service, dataStore)
    }

    func insertInFlightRetryForTest(_ messageID: UUID) {
        inFlightRetries.insert(messageID)
    }

    func setPendingAckForTest(_ tracking: PendingAck) {
        pendingAcks[tracking.messageID] = tracking
    }

    /// Ends the status-event stream and returns everything `stream` buffered.
    /// Subscribe via `statusEvents()` before triggering the behavior under
    /// test: registration is synchronous and production yields happen before
    /// the triggering call returns, so the drained array is complete.
    nonisolated func drainStatusEvents(_ stream: AsyncStream<MessageStatusEvent>) async -> [MessageStatusEvent] {
        finishStatusEvents()
        var events: [MessageStatusEvent] = []
        for await event in stream {
            events.append(event)
        }
        return events
    }

    /// The concrete session injected by `createForTesting`, for test-only hooks
    /// (`dispatchForTesting`, `subscriberCountForTest`) the protocol does not carry.
    var sessionForTest: MeshCoreSession {
        guard let concrete = session as? MeshCoreSession else {
            fatalError("MessageService under test must be built with a concrete MeshCoreSession")
        }
        return concrete
    }

    func installSelfInfoForTest(publicKey: Data = Data(repeating: 0xAB, count: 32)) async {
        await sessionForTest.installSelfInfoForTest(.testSelfInfo(publicKey: publicKey))
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

extension Array where Element == MessageStatusEvent {
    /// Message IDs carried by `.failed` events, in yield order.
    var failedIDs: [UUID] {
        compactMap {
            if case .failed(let id) = $0 { return id }
            return nil
        }
    }

    /// Message IDs carried by `.resent` events, in yield order.
    var resentIDs: [UUID] {
        compactMap {
            if case .resent(let id) = $0 { return id }
            return nil
        }
    }

    /// Message IDs carried by `.statusResolved` events, in yield order.
    var resolvedIDs: [UUID] {
        compactMap {
            if case .statusResolved(let id, _, _) = $0 { return id }
            return nil
        }
    }

    /// Payloads carried by `.retrying` events, in yield order.
    var retryUpdates: [(messageID: UUID, attempt: Int, maxAttempts: Int)] {
        compactMap {
            if case .retrying(let id, let attempt, let maxAttempts) = $0 {
                return (id, attempt, maxAttempts)
            }
            return nil
        }
    }
}
