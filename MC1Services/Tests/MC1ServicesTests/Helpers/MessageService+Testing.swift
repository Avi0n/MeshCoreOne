import Foundation
import MeshCoreTestSupport
import Testing
@testable import MC1Services
@testable import MeshCore

extension MessageService {
    static func createForTesting() async throws -> (MessageService, PersistenceStore) {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        let transport = SimulatorMockTransport()
        let session = MeshCoreSession(transport: transport)
        let service = MessageService(session: session, dataStore: dataStore)
        return (service, dataStore)
    }

    func insertInFlightRetryForTest(_ messageID: UUID) {
        inFlightRetries.insert(messageID)
    }

    func setPendingAckForTest(_ tracking: PendingAck) {
        pendingAcks[tracking.messageID] = tracking
    }

    func setRecentlyFailedAckForTest(code: Data, messageID: UUID, failedAt: Date) {
        recentlyFailedAcks[code] = (messageID, failedAt)
    }

    func setMessageFailedHandlerForTest(_ handler: @escaping @Sendable (UUID) async -> Void) {
        messageFailedHandler = handler
    }

    var sessionForTest: MeshCoreSession { session }

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

actor AckConfirmationTracker {
    var confirmations: [(ackCode: UInt32, roundTripTime: UInt32?)] = []

    func record(ackCode: UInt32, roundTripTime: UInt32?) {
        confirmations.append((ackCode, roundTripTime))
    }

    func waitForConfirmationCount(
        _ expectedCount: Int,
        timeout: Duration = .milliseconds(500)
    ) async {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        while ContinuousClock.now < deadline {
            if confirmations.count == expectedCount {
                return
            }
            await Task.yield()
        }
        Issue.record("confirmation count did not reach \(expectedCount) within \(timeout)")
    }
}
