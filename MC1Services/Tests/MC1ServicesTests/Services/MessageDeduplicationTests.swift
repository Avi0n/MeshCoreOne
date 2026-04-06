import Foundation
import Testing
@testable import MC1Services

@Suite("Message Deduplication Tests")
struct MessageDeduplicationTests {

    // MARK: - Fallback Key Format Tests

    @Test("DM fallback key is deterministic")
    func dmFallbackKeyDeterministic() {
        let contactID = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
        let key1 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: contactID, channelIndex: nil,
            senderNodeName: nil, timestamp: 1704067200, content: "Hello world"
        )
        let key2 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: contactID, channelIndex: nil,
            senderNodeName: nil, timestamp: 1704067200, content: "Hello world"
        )
        #expect(key1 == key2)
        #expect(key1.hasPrefix("dm-"))
    }

    @Test("Channel fallback key is deterministic")
    func channelFallbackKeyDeterministic() {
        let key1 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: nil, channelIndex: 0,
            senderNodeName: "Alice", timestamp: 1704067200, content: "Hello channel"
        )
        let key2 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: nil, channelIndex: 0,
            senderNodeName: "Alice", timestamp: 1704067200, content: "Hello channel"
        )
        #expect(key1 == key2)
        #expect(key1.hasPrefix("ch-"))
    }

    @Test("DM and channel fallback keys never collide for same content")
    func dmAndChannelKeysNeverCollide() {
        let contactID = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
        let dmKey = SyncCoordinator.fallbackDeduplicationKey(
            contactID: contactID, channelIndex: nil,
            senderNodeName: nil, timestamp: 1704067200, content: "Hello"
        )
        let channelKey = SyncCoordinator.fallbackDeduplicationKey(
            contactID: nil, channelIndex: 0,
            senderNodeName: "Alice", timestamp: 1704067200, content: "Hello"
        )
        #expect(dmKey != channelKey)
    }

    @Test("Different contacts produce different DM fallback keys")
    func differentContactsDifferentKeys() {
        let contact1 = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
        let contact2 = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
        let key1 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: contact1, channelIndex: nil,
            senderNodeName: nil, timestamp: 1704067200, content: "Hello"
        )
        let key2 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: contact2, channelIndex: nil,
            senderNodeName: nil, timestamp: 1704067200, content: "Hello"
        )
        #expect(key1 != key2)
    }

    @Test("Different channel indices produce different channel fallback keys")
    func differentChannelsDifferentKeys() {
        let key1 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: nil, channelIndex: 0,
            senderNodeName: "Alice", timestamp: 1704067200, content: "Hello"
        )
        let key2 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: nil, channelIndex: 1,
            senderNodeName: "Alice", timestamp: 1704067200, content: "Hello"
        )
        #expect(key1 != key2)
    }

    // MARK: - Retry Dedup Stability

    @Test("DM retry attempts with same content produce identical dedup keys")
    func dmRetryAttemptsProduceSameKey() {
        let contactID = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
        let timestamp: UInt32 = 1704067200
        let text = "Hello mesh"

        // Simulate two retry attempts: same contact, timestamp, and text
        let keyAttempt0 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: contactID, channelIndex: nil,
            senderNodeName: nil, timestamp: timestamp, content: text
        )
        let keyAttempt1 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: contactID, channelIndex: nil,
            senderNodeName: nil, timestamp: timestamp, content: text
        )
        #expect(keyAttempt0 == keyAttempt1,
                "Retry attempts with the same content must produce identical dedup keys")
    }

    @Test("Channel retry attempts with same content produce identical dedup keys")
    func channelRetryAttemptsProduceSameKey() {
        let timestamp: UInt32 = 1704067200
        let text = "Hello channel"

        let keyAttempt0 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: nil, channelIndex: 2,
            senderNodeName: "Bob", timestamp: timestamp, content: text
        )
        let keyAttempt1 = SyncCoordinator.fallbackDeduplicationKey(
            contactID: nil, channelIndex: 2,
            senderNodeName: "Bob", timestamp: timestamp, content: text
        )
        #expect(keyAttempt0 == keyAttempt1,
                "Channel retry attempts with the same content must produce identical dedup keys")
    }

    // MARK: - isDuplicateMessage via MockPersistenceStore

    @Test("isDuplicateMessage returns false when no matching key exists")
    func noDuplicateWhenEmpty() async throws {
        let store = MockPersistenceStore()
        let result = try await store.isDuplicateMessage(deduplicationKey: "test-key")
        #expect(!result)
    }

    @Test("isDuplicateMessage returns true when matching key exists")
    func duplicateWhenKeyExists() async throws {
        let store = MockPersistenceStore()
        let dto = MessageDTO(
            id: UUID(),
            deviceID: UUID(),
            contactID: nil,
            channelIndex: 0,
            text: "Hello",
            timestamp: 1704067200,
            createdAt: Date(),
            direction: .incoming,
            status: .delivered,
            textType: .plain,
            ackCode: nil,
            pathLength: 1,
            snr: nil,
            senderKeyPrefix: nil,
            senderNodeName: "Alice",
            isRead: false,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0,
            deduplicationKey: "test-key"
        )
        try await store.saveMessage(dto)

        let result = try await store.isDuplicateMessage(deduplicationKey: "test-key")
        #expect(result)
    }
}
