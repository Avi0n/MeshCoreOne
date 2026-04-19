import Foundation
import MeshCoreTestSupport
@testable import MC1Services

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

    func setMessageFailedHandlerForTest(_ handler: @escaping @Sendable (UUID) async -> Void) {
        messageFailedHandler = handler
    }
}

actor FailedMessageTracker {
    var failedIDs: [UUID] = []
    func record(_ id: UUID) { failedIDs.append(id) }
}
