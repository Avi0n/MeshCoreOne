import Testing
import Foundation
import MeshCoreTestSupport
@testable import MC1Services

@Suite("MessageService ACK Tests")
struct MessageServiceACKTests {

    private let testDeviceID = UUID()

    private func makePending(
        messageID: UUID = UUID(),
        contactID: UUID = UUID(),
        ackCodes: Set<Data>,
        sentAt: Date = Date(),
        timeout: TimeInterval = 30.0,
        isDelivered: Bool = false
    ) -> PendingAck {
        PendingAck(
            messageID: messageID,
            contactID: contactID,
            ackCodes: ackCodes,
            sentAt: sentAt,
            timeout: timeout,
            isDelivered: isDelivered
        )
    }

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

        await service.startAckExpiryChecking()
        #expect(await service.isAckExpiryCheckingActive)
        await service.stopAckExpiryChecking()
    }

    // MARK: - checkExpiredAcks

    @Test("checkExpiredAcks marks expired ACK as failed")
    func expiredAckMarkedFailed() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()

        let message = MessageDTO.testDirectMessage(
            id: messageID,
            radioID: testDeviceID,
            status: .sent
        )
        try await dataStore.saveMessage(message)

        let tracker = FailedMessageTracker()
        await service.setMessageFailedHandlerForTest { id in
            await tracker.record(id)
        }

        let ackCode = Data([0x01, 0x02, 0x03, 0x04])
        await service.setPendingAckForTest(
            makePending(
                messageID: messageID,
                ackCodes: [ackCode],
                sentAt: Date().addingTimeInterval(-60),
                timeout: 30.0
            )
        )

        try await service.checkExpiredAcks()

        let fetched = try await dataStore.fetchMessage(id: messageID)
        #expect(fetched?.status == .failed)

        let failedIDs = await tracker.failedIDs
        #expect(failedIDs.contains(messageID))
    }

    @Test("ACK timeout enters retrying grace window before failure")
    func ackTimeoutEntersRetryingGraceWindowBeforeFailure() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()

        let message = MessageDTO.testDirectMessage(
            id: messageID,
            radioID: testDeviceID,
            status: .sent
        )
        try await dataStore.saveMessage(message)

        let tracker = FailedMessageTracker()
        let retryTracker = RetryStatusTracker()
        await service.setMessageFailedHandlerForTest { id in
            await tracker.record(id)
        }
        await service.setRetryStatusHandler { messageID, attempt, maxAttempts in
            await retryTracker.record(
                messageID: messageID,
                attempt: attempt,
                maxAttempts: maxAttempts
            )
        }

        let ackCode = Data([0x11, 0x22, 0x33, 0x44])
        await service.setPendingAckForTest(
            makePending(
                messageID: messageID,
                ackCodes: [ackCode],
                sentAt: Date().addingTimeInterval(-31),
                timeout: 30.0
            )
        )

        try await service.checkExpiredAcks()

        let fetched = try await dataStore.fetchMessage(id: messageID)
        #expect(fetched?.status == .retrying)
        #expect(await service.pendingAckCount == 1)

        let failedIDs = await tracker.failedIDs
        #expect(!failedIDs.contains(messageID))
        let retryUpdates = await retryTracker.updates
        #expect(retryUpdates.count == 1)
        #expect(retryUpdates.first?.messageID == messageID)
    }

    @Test("checkExpiredAcks preserves non-expired ACK")
    func nonExpiredAckSurvives() async throws {
        let (service, _) = try await MessageService.createForTesting()

        let ackCode = Data([0x05, 0x06, 0x07, 0x08])
        await service.setPendingAckForTest(
            makePending(ackCodes: [ackCode], sentAt: Date(), timeout: 30.0)
        )

        try await service.checkExpiredAcks()

        #expect(await service.pendingAckCount == 1, "Non-expired ACK should survive")
    }

    @Test("checkExpiredAcks skips already-delivered ACK")
    func deliveredAckSkipped() async throws {
        let (service, _) = try await MessageService.createForTesting()

        let ackCode = Data([0x0D, 0x0E, 0x0F, 0x10])
        await service.setPendingAckForTest(
            makePending(
                ackCodes: [ackCode],
                sentAt: Date().addingTimeInterval(-60),
                timeout: 30.0,
                isDelivered: true
            )
        )

        try await service.checkExpiredAcks()

        #expect(await service.pendingAckCount == 1, "Delivered ACK should not be expired")
    }

    // MARK: - failAllPendingMessages

    @Test("failAllPendingMessages fails all non-delivered and calls handler")
    func failAllPending() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID1 = UUID()
        let messageID2 = UUID()

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID1, radioID: testDeviceID, status: .sent)
        )
        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID2, radioID: testDeviceID, status: .sent)
        )

        let tracker = FailedMessageTracker()
        await service.setMessageFailedHandlerForTest { id in
            await tracker.record(id)
        }

        await service.setPendingAckForTest(
            makePending(messageID: messageID1, ackCodes: [Data([0x01, 0x02, 0x03, 0x04])])
        )
        await service.setPendingAckForTest(
            makePending(messageID: messageID2, ackCodes: [Data([0x05, 0x06, 0x07, 0x08])])
        )

        try await service.failAllPendingMessages()

        let msg1 = try await dataStore.fetchMessage(id: messageID1)
        let msg2 = try await dataStore.fetchMessage(id: messageID2)
        #expect(msg1?.status == .failed)
        #expect(msg2?.status == .failed)

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

        await service.setPendingAckForTest(
            makePending(
                messageID: deliveredID,
                ackCodes: [Data([0x01, 0x02, 0x03, 0x04])],
                isDelivered: true
            )
        )
        await service.setPendingAckForTest(
            makePending(messageID: pendingID, ackCodes: [Data([0x05, 0x06, 0x07, 0x08])])
        )

        try await service.failAllPendingMessages()

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

        await service.setPendingAckForTest(
            makePending(messageID: messageID, ackCodes: [Data([0x01, 0x02, 0x03, 0x04])])
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
        await service.setPendingAckForTest(
            makePending(
                messageID: messageID,
                ackCodes: [ackCode],
                sentAt: Date().addingTimeInterval(-10)
            )
        )

        await service.handleAcknowledgement(code: ackCode, tripTime: 250)

        let fetched = try await dataStore.fetchMessage(id: messageID)
        #expect(fetched?.status == .delivered)
        #expect(fetched?.roundTripTime == 250,
                "Should use firmware tripTime (250ms), not Date()-based (~10000ms)")
    }

    @Test("handleAcknowledgement leaves roundTripTime nil when firmware does not supply tripTime")
    func nilTripTimeLeavesRoundTripTimeNil() async throws {
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
        await service.setPendingAckForTest(
            makePending(
                messageID: messageID,
                ackCodes: [ackCode],
                sentAt: Date().addingTimeInterval(-2)
            )
        )

        await service.handleAcknowledgement(code: ackCode, tripTime: nil)

        let fetched = try await dataStore.fetchMessage(id: messageID)
        #expect(fetched?.status == .delivered)
        #expect(fetched?.roundTripTime == nil,
                "nil tripTime must not be replaced by a fabricated Date()-based RTT")
    }

    // MARK: - Multi-attempt (Issue #283)

    @Test("handleAcknowledgement matches any CRC accumulated across retry attempts")
    func multiAttemptLateAckDelivers() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()

        let message = MessageDTO.testDirectMessage(
            id: messageID,
            radioID: testDeviceID,
            status: .pending
        )
        try await dataStore.saveMessage(message)

        // Simulate three retry attempts, each producing a different CRC
        // (firmware hashes attempt index into the ack code).
        let attempt0 = Data([0xAA, 0xAA, 0xAA, 0xAA])
        let attempt1 = Data([0xBB, 0xBB, 0xBB, 0xBB])
        let attempt2 = Data([0xCC, 0xCC, 0xCC, 0xCC])
        await service.setPendingAckForTest(
            makePending(messageID: messageID, ackCodes: [attempt0, attempt1, attempt2])
        )

        // A late ACK for attempt 0's CRC arrives after the retry loop has moved
        // on to attempt 2 — must still deliver.
        await service.handleAcknowledgement(code: attempt0, tripTime: 500)

        let fetched = try await dataStore.fetchMessage(id: messageID)
        #expect(fetched?.status == .delivered,
                "Late ACK from an earlier attempt must still mark delivered")
        #expect(fetched?.roundTripTime == 500)
        #expect(await service.pendingAckCount == 0,
                "Entry should be removed after delivery (no sibling accumulation)")
    }

    @Test("handleAcknowledgement updates contact lastMessageDate on late ACK")
    func lateAckUpdatesContactLastMessage() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let radioID = testDeviceID
        try await dataStore.saveDevice(DeviceDTO.testDevice(id: radioID, radioID: radioID))

        let frame = ContactFrame(
            publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
            type: .chat,
            flags: 0,
            outPathLength: 2,
            outPath: Data([0x01, 0x02]),
            name: "LateAckContact",
            lastAdvertTimestamp: UInt32(Date().timeIntervalSince1970),
            latitude: 0,
            longitude: 0,
            lastModified: UInt32(Date().timeIntervalSince1970)
        )
        let contactID = try await dataStore.saveContact(radioID: radioID, from: frame)

        let messageID = UUID()
        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(
                id: messageID,
                radioID: radioID,
                contactID: contactID,
                status: .pending
            )
        )

        let ackCode = Data([0x11, 0x22, 0x33, 0x44])
        await service.setPendingAckForTest(
            makePending(messageID: messageID, contactID: contactID, ackCodes: [ackCode])
        )

        let before = Date()
        await service.handleAcknowledgement(code: ackCode, tripTime: 500)

        let contact = try await dataStore.fetchContact(id: contactID)
        #expect(contact?.lastMessageDate != nil,
                "Late ACK path must update contact lastMessageDate")
        if let date = contact?.lastMessageDate {
            #expect(date >= before,
                    "Contact lastMessageDate should be updated to approximately now")
        }
    }

    @Test("trackPendingAck on retry resets sentAt so checkExpiredAcks preserves a retrying message")
    func retryTrackingResetsSentAt() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let contactID = UUID()

        let message = MessageDTO.testDirectMessage(
            id: messageID,
            radioID: testDeviceID,
            status: .sent
        )
        try await dataStore.saveMessage(message)

        // Seed attempt 0 with sentAt well past its timeout window.
        let ancient = Date().addingTimeInterval(-60)
        await service.setPendingAckForTest(
            makePending(
                messageID: messageID,
                contactID: contactID,
                ackCodes: [Data([0xAA, 0xAA, 0xAA, 0xAA])],
                sentAt: ancient,
                timeout: 30.0
            )
        )

        // Retry attempt 1 registers a new ackCode and a fresh timeout window.
        // sentAt must also advance, otherwise checkExpiredAcks will fail the
        // entry immediately even though the retry is actively in flight.
        await service.trackPendingAck(
            messageID: messageID,
            contactID: contactID,
            ackCode: Data([0xBB, 0xBB, 0xBB, 0xBB]),
            timeout: 30.0
        )

        try await service.checkExpiredAcks()

        #expect(await service.pendingAckCount == 1,
                "Retry attempt must preserve the entry, not expire it")
        let fetched = try await dataStore.fetchMessage(id: messageID)
        #expect(fetched?.status != .failed,
                "Retry must not flicker to .failed while in flight")
    }

    @Test("finalizeSend preserves roundTripTime after listener-won delivery")
    func finalizeSendPreservesListenerRTT() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let contactID = UUID()
        let radioID = testDeviceID
        let publicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })
        let ackCode = Data([0xAA, 0xBB, 0xCC, 0xDD])

        let message = MessageDTO.testDirectMessage(
            id: messageID,
            radioID: radioID,
            contactID: contactID,
            status: .sent
        )
        try await dataStore.saveMessage(message)

        await service.setPendingAckForTest(
            makePending(
                messageID: messageID,
                contactID: contactID,
                ackCodes: [ackCode]
            )
        )

        // Listener path fires first: writes .delivered + RTT=500 and removes the entry.
        await service.handleAcknowledgement(code: ackCode, tripTime: 500)

        let afterListener = try await dataStore.fetchMessage(id: messageID)
        #expect(afterListener?.roundTripTime == 500,
                "Listener should have written RTT=500 before finalizeSend runs")

        // Retry loop's waitForEvent matched the same ACK, so finalizeSend runs
        // with sentInfo != nil. It must NOT clobber the listener-written RTT.
        let sentInfo = MessageSentInfo(
            type: 0,
            expectedAck: ackCode,
            suggestedTimeoutMs: 5000
        )
        _ = try await service.finalizeSend(
            messageID: messageID,
            contactID: contactID,
            radioID: radioID,
            publicKey: publicKey,
            sentInfo: sentInfo,
            initialPathLength: 0
        )

        let final = try await dataStore.fetchMessage(id: messageID)
        #expect(final?.status == .delivered)
        #expect(final?.roundTripTime == 500,
                "finalizeSend must not clobber listener-written RTT with nil")
    }

    @Test("handleAcknowledgement is a no-op when no entry matches the ackCode")
    func unmatchedAckIsNoOp() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()

        let message = MessageDTO.testDirectMessage(
            id: messageID,
            radioID: testDeviceID,
            status: .sent
        )
        try await dataStore.saveMessage(message)

        await service.handleAcknowledgement(code: Data([0xDE, 0xAD, 0xBE, 0xEF]), tripTime: 100)

        let fetched = try await dataStore.fetchMessage(id: messageID)
        #expect(fetched?.status == .sent,
                "Unmatched ACK must not change message status")
        #expect(await service.pendingAckCount == 0)
    }

    // MARK: - failMessageAndRethrow cleanup

    @Test("failMessageAndRethrow removes pendingAcks entry and rethrows")
    func failMessageRemovesPendingAck() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()

        let message = MessageDTO.testDirectMessage(
            id: messageID,
            radioID: testDeviceID,
            status: .pending
        )
        try await dataStore.saveMessage(message)

        await service.setPendingAckForTest(
            makePending(messageID: messageID, ackCodes: [Data([0x01, 0x02, 0x03, 0x04])])
        )
        #expect(await service.pendingAckCount == 1)

        await #expect(throws: MessageServiceError.self) {
            try await service.failMessageAndRethrow(
                MeshCoreError.notConnected,
                messageID: messageID
            )
        }

        #expect(await service.pendingAckCount == 0,
                "Throw path must remove pendingAcks entry to prevent leaks")
        let fetched = try await dataStore.fetchMessage(id: messageID)
        #expect(fetched?.status == .failed)
    }

    // MARK: - pendingAckCount

    @Test("pendingAckCount reflects count correctly")
    func pendingAckCountReflectsCorrectly() async throws {
        let (service, _) = try await MessageService.createForTesting()

        #expect(await service.pendingAckCount == 0)

        await service.setPendingAckForTest(
            makePending(ackCodes: [Data([0x01, 0x02, 0x03, 0x04])])
        )
        #expect(await service.pendingAckCount == 1)

        await service.setPendingAckForTest(
            makePending(ackCodes: [Data([0x05, 0x06, 0x07, 0x08])])
        )
        #expect(await service.pendingAckCount == 2)
    }

    // MARK: - Late-ACK Grace Window

    @Test("ACK within grace window reconciles .retrying → .delivered")
    func ackWithinGraceReconciles() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let contactID = UUID()
        let ackCode = Data([0xDE, 0xAD, 0xBE, 0xEF])

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(
                id: messageID,
                contactID: contactID,
                status: .sent,
                ackCode: ackCode.ackCodeUInt32
            )
        )
        await service.setPendingAckForTest(
            PendingAck(
                messageID: messageID,
                contactID: contactID,
                ackCodes: [ackCode],
                sentAt: Date(timeIntervalSinceNow: -31),
                timeout: 30
            )
        )

        try await service.checkExpiredAcks()
        let afterTimeout = try await dataStore.fetchMessage(id: messageID)
        #expect(afterTimeout?.status == .retrying)

        await service.handleAcknowledgement(code: ackCode, tripTime: 99)

        let stored = try await dataStore.fetchMessage(id: messageID)
        #expect(stored?.status == .delivered)
    }

    @Test("late ACK after five-second grace window stays .failed")
    func lateAckAfterFiveSecondGraceWindowStaysFailed() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let ackCode = Data([0x01, 0x23, 0x45, 0x67])

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(
                id: messageID,
                status: .failed,
                ackCode: ackCode.ackCodeUInt32
            )
        )
        await service.setRecentlyFailedAckForTest(
            code: ackCode,
            messageID: messageID,
            failedAt: Date(timeIntervalSinceNow: -6)
        )

        await service.handleAcknowledgement(code: ackCode, tripTime: 99)

        let stored = try await dataStore.fetchMessage(id: messageID)
        #expect(stored?.status == .failed)
    }

    @Test("late ACK reconciliation notifies ACK confirmation handler")
    func lateAckReconciliationNotifiesAckConfirmationHandler() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let ackCode = Data([0x10, 0x32, 0x54, 0x76])
        let tracker = AckConfirmationTracker()

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(
                id: messageID,
                status: .failed,
                ackCode: ackCode.ackCodeUInt32
            )
        )
        await service.setRecentlyFailedAckForTest(
            code: ackCode,
            messageID: messageID,
            failedAt: Date()
        )
        await service.setAckConfirmationHandler { ackCode, roundTripTime in
            Task {
                await tracker.record(ackCode: ackCode, roundTripTime: roundTripTime)
            }
        }

        await service.handleAcknowledgement(code: ackCode, tripTime: 99)
        await tracker.waitForConfirmationCount(1)

        let stored = try await dataStore.fetchMessage(id: messageID)
        #expect(stored?.status == .delivered)
        let confirmations = await tracker.confirmations
        #expect(confirmations.count == 1)
        #expect(confirmations.first?.ackCode == ackCode.ackCodeUInt32)
        #expect(confirmations.first?.roundTripTime == 99)
    }

    @Test("late ACK for a message that was never in the ring stays .failed")
    func lateAckWithoutRingEntryNoOp() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let ackCode = Data([0xCA, 0xFE, 0xBA, 0xBE])

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(
                id: messageID,
                status: .failed,
                ackCode: ackCode.ackCodeUInt32
            )
        )

        await service.handleAcknowledgement(code: ackCode, tripTime: 99)

        let stored = try await dataStore.fetchMessage(id: messageID)
        #expect(stored?.status == .failed)
    }
}
