import Testing
import Foundation
import MeshCoreTestSupport
@testable import MC1Services

@Suite("MessageService ACK Tests")
struct MessageServiceACKTests {

    private let testDeviceID = UUID()

    // MARK: - ACK Expiry Checking Toggle

    @Test("isAckExpiryCheckingActive toggles correctly")
    func ackExpiryCheckingToggles() async throws {
        let (service, _) = try await MessageService.createForTesting()

        #expect(await !service.isAckExpiryCheckingActive)

        await service.startAckExpiryChecking()
        #expect(await service.isAckExpiryCheckingActive)

        await service.stopAckExpiryChecking()
        #expect(await !service.isAckExpiryCheckingActive)
    }

    @Test("stopAckExpiryChecking cancels the background task")
    func stopCancelsTask() async throws {
        let (service, _) = try await MessageService.createForTesting()

        await service.startAckExpiryChecking()
        #expect(await service.isAckExpiryCheckingActive)

        await service.stopAckExpiryChecking()
        #expect(await !service.isAckExpiryCheckingActive)

        // Start again to verify it works after stop
        await service.startAckExpiryChecking()
        #expect(await service.isAckExpiryCheckingActive)
        await service.stopAckExpiryChecking()
    }

    // MARK: - checkExpiredAcks

    @Test("checkExpiredAcks marks expired non-retry-managed ACK as failed")
    func expiredAckMarkedFailed() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()

        // Save a message so updateMessageStatus can find it
        let message = MessageDTO.testDirectMessage(
            id: messageID,
            radioID: testDeviceID,
            status: .sent
        )
        try await dataStore.saveMessage(message)

        // Track handler calls
        let tracker = FailedMessageTracker()
        await service.setMessageFailedHandlerForTest { id in
            await tracker.record(id)
        }

        // Add an expired pending ACK
        let ackCode = Data([0x01, 0x02, 0x03, 0x04])
        let expired = PendingAck(
            messageID: messageID,
            ackCode: ackCode,
            sentAt: Date().addingTimeInterval(-60),
            timeout: 30.0
        )
        await service.setPendingAckForTest(ackCode: ackCode, tracking: expired)

        try await service.checkExpiredAcks()

        // Verify message status updated to failed
        let fetched = try await dataStore.fetchMessage(id: messageID)
        #expect(fetched?.status == .failed)

        // Verify handler called
        let failedIDs = await tracker.failedIDs
        #expect(failedIDs.contains(messageID))
    }

    @Test("checkExpiredAcks preserves non-expired ACK")
    func nonExpiredAckSurvives() async throws {
        let (service, _) = try await MessageService.createForTesting()

        let ackCode = Data([0x05, 0x06, 0x07, 0x08])
        let fresh = PendingAck(
            messageID: UUID(),
            ackCode: ackCode,
            sentAt: Date(), // just now — not expired
            timeout: 30.0
        )
        await service.setPendingAckForTest(ackCode: ackCode, tracking: fresh)

        try await service.checkExpiredAcks()

        #expect(await service.pendingAckCount == 1, "Non-expired ACK should survive")
    }

    @Test("checkExpiredAcks skips retry-managed ACK")
    func retryManagedAckSkipped() async throws {
        let (service, _) = try await MessageService.createForTesting()

        let ackCode = Data([0x09, 0x0A, 0x0B, 0x0C])
        let retryManaged = PendingAck(
            messageID: UUID(),
            ackCode: ackCode,
            sentAt: Date().addingTimeInterval(-60),
            timeout: 30.0,
            isRetryManaged: true
        )
        await service.setPendingAckForTest(ackCode: ackCode, tracking: retryManaged)

        try await service.checkExpiredAcks()

        #expect(await service.pendingAckCount == 1, "Retry-managed ACK should not be expired")
    }

    @Test("checkExpiredAcks skips already-delivered ACK")
    func deliveredAckSkipped() async throws {
        let (service, _) = try await MessageService.createForTesting()

        let ackCode = Data([0x0D, 0x0E, 0x0F, 0x10])
        var delivered = PendingAck(
            messageID: UUID(),
            ackCode: ackCode,
            sentAt: Date().addingTimeInterval(-60),
            timeout: 30.0
        )
        delivered.isDelivered = true
        await service.setPendingAckForTest(ackCode: ackCode, tracking: delivered)

        try await service.checkExpiredAcks()

        #expect(await service.pendingAckCount == 1, "Delivered ACK should not be expired")
    }

    // MARK: - cleanupDeliveredAcks

    @Test("cleanupDeliveredAcks removes delivered entries and preserves non-delivered")
    func cleanupRemovesDelivered() async throws {
        let (service, _) = try await MessageService.createForTesting()

        // Add a delivered ACK
        let deliveredCode = Data([0x01, 0x02, 0x03, 0x04])
        var deliveredAck = PendingAck(
            messageID: UUID(),
            ackCode: deliveredCode,
            sentAt: Date(),
            timeout: 30.0
        )
        deliveredAck.isDelivered = true
        await service.setPendingAckForTest(ackCode: deliveredCode, tracking: deliveredAck)

        // Add a non-delivered ACK
        let pendingCode = Data([0x05, 0x06, 0x07, 0x08])
        let pendingAck = PendingAck(
            messageID: UUID(),
            ackCode: pendingCode,
            sentAt: Date(),
            timeout: 30.0
        )
        await service.setPendingAckForTest(ackCode: pendingCode, tracking: pendingAck)

        #expect(await service.pendingAckCount == 2)

        await service.cleanupDeliveredAcks()

        #expect(await service.pendingAckCount == 1, "Only non-delivered ACK should remain")
    }

    // MARK: - failAllPendingMessages

    @Test("failAllPendingMessages fails all non-delivered and calls handler")
    func failAllPending() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID1 = UUID()
        let messageID2 = UUID()

        // Save messages
        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID1, radioID: testDeviceID, status: .sent)
        )
        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID2, radioID: testDeviceID, status: .sent)
        )

        // Track handler calls
        let tracker = FailedMessageTracker()
        await service.setMessageFailedHandlerForTest { id in
            await tracker.record(id)
        }

        // Add pending ACKs
        let code1 = Data([0x01, 0x02, 0x03, 0x04])
        await service.setPendingAckForTest(
            ackCode: code1,
            tracking: PendingAck(messageID: messageID1, ackCode: code1, sentAt: Date(), timeout: 30.0)
        )
        let code2 = Data([0x05, 0x06, 0x07, 0x08])
        await service.setPendingAckForTest(
            ackCode: code2,
            tracking: PendingAck(messageID: messageID2, ackCode: code2, sentAt: Date(), timeout: 30.0)
        )

        try await service.failAllPendingMessages()

        // Verify statuses
        let msg1 = try await dataStore.fetchMessage(id: messageID1)
        let msg2 = try await dataStore.fetchMessage(id: messageID2)
        #expect(msg1?.status == .failed)
        #expect(msg2?.status == .failed)

        // Verify handler called for both
        let failedIDs = await tracker.failedIDs
        #expect(failedIDs.count == 2)
        #expect(failedIDs.contains(messageID1))
        #expect(failedIDs.contains(messageID2))
    }

    @Test("failAllPendingMessages skips already-delivered")
    func failAllSkipsDelivered() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let deliveredID = UUID()
        let pendingID = UUID()

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: pendingID, radioID: testDeviceID, status: .sent)
        )

        // Delivered ACK
        let dCode = Data([0x01, 0x02, 0x03, 0x04])
        var dAck = PendingAck(messageID: deliveredID, ackCode: dCode, sentAt: Date(), timeout: 30.0)
        dAck.isDelivered = true
        await service.setPendingAckForTest(ackCode: dCode, tracking: dAck)

        // Pending ACK
        let pCode = Data([0x05, 0x06, 0x07, 0x08])
        await service.setPendingAckForTest(
            ackCode: pCode,
            tracking: PendingAck(messageID: pendingID, ackCode: pCode, sentAt: Date(), timeout: 30.0)
        )

        try await service.failAllPendingMessages()

        // Only the pending one should be failed
        let msg = try await dataStore.fetchMessage(id: pendingID)
        #expect(msg?.status == .failed)
    }

    // MARK: - stopAndFailAllPending

    @Test("stopAndFailAllPending stops checking and fails all pending")
    func stopAndFailAll() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID, radioID: testDeviceID, status: .sent)
        )

        await service.startAckExpiryChecking()
        #expect(await service.isAckExpiryCheckingActive)

        let code = Data([0x01, 0x02, 0x03, 0x04])
        await service.setPendingAckForTest(
            ackCode: code,
            tracking: PendingAck(messageID: messageID, ackCode: code, sentAt: Date(), timeout: 30.0)
        )

        try await service.stopAndFailAllPending()

        #expect(await !service.isAckExpiryCheckingActive)
        let msg = try await dataStore.fetchMessage(id: messageID)
        #expect(msg?.status == .failed)
    }

    // MARK: - Trip Time Preference

    @Test("handleAcknowledgement uses firmware tripTime when provided")
    func firmwareTripTimePreferred() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()

        let message = MessageDTO.testDirectMessage(
            id: messageID,
            radioID: testDeviceID,
            status: .sent,
            ackCode: 0xDEADBEEF
        )
        try await dataStore.saveMessage(message)

        let ackCode = Data([0xEF, 0xBE, 0xAD, 0xDE]) // 0xDEADBEEF LE
        let pending = PendingAck(
            messageID: messageID,
            ackCode: ackCode,
            sentAt: Date().addingTimeInterval(-10), // 10 seconds ago
            timeout: 30.0
        )
        await service.setPendingAckForTest(ackCode: ackCode, tracking: pending)

        // Provide firmware trip time of 250ms
        await service.handleAcknowledgement(code: ackCode, tripTime: 250)

        let fetched = try await dataStore.fetchMessage(id: messageID)
        #expect(fetched?.status == .delivered)
        #expect(fetched?.roundTripTime == 250,
                "Should use firmware tripTime (250ms), not Date()-based (~10000ms)")
    }

    @Test("handleAcknowledgement falls back to Date() calculation when tripTime is nil")
    func fallbackToDateCalculation() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()

        let message = MessageDTO.testDirectMessage(
            id: messageID,
            radioID: testDeviceID,
            status: .sent,
            ackCode: 0xCAFEBABE
        )
        try await dataStore.saveMessage(message)

        let ackCode = Data([0xBE, 0xBA, 0xFE, 0xCA]) // 0xCAFEBABE LE
        let pending = PendingAck(
            messageID: messageID,
            ackCode: ackCode,
            sentAt: Date().addingTimeInterval(-2), // 2 seconds ago
            timeout: 30.0
        )
        await service.setPendingAckForTest(ackCode: ackCode, tracking: pending)

        // Pass nil tripTime — should fall back to Date()-based calculation
        await service.handleAcknowledgement(code: ackCode, tripTime: nil)

        let fetched = try await dataStore.fetchMessage(id: messageID)
        #expect(fetched?.status == .delivered)
        // Date()-based RTT should be roughly 2000ms (within a reasonable range)
        if let rtt = fetched?.roundTripTime {
            #expect(rtt >= 1500 && rtt <= 5000,
                    "Date()-based RTT should be ~2000ms, got \(rtt)ms")
        }
    }

    // MARK: - pendingAckCount

    @Test("pendingAckCount reflects count correctly")
    func pendingAckCountReflectsCorrectly() async throws {
        let (service, _) = try await MessageService.createForTesting()

        #expect(await service.pendingAckCount == 0)

        let code1 = Data([0x01, 0x02, 0x03, 0x04])
        await service.setPendingAckForTest(
            ackCode: code1,
            tracking: PendingAck(messageID: UUID(), ackCode: code1, sentAt: Date(), timeout: 30.0)
        )
        #expect(await service.pendingAckCount == 1)

        let code2 = Data([0x05, 0x06, 0x07, 0x08])
        await service.setPendingAckForTest(
            ackCode: code2,
            tracking: PendingAck(messageID: UUID(), ackCode: code2, sentAt: Date(), timeout: 30.0)
        )
        #expect(await service.pendingAckCount == 2)
    }
}
