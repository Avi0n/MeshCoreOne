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

        let statusEvents = service.statusEvents()

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

        let failedIDs = await service.drainStatusEvents(statusEvents).failedIDs
        #expect(failedIDs.contains(messageID))
    }

    @Test("ACK timeout keeps message .sent through the grace window so a late ACK can still reconcile")
    func ackTimeoutStaysSentDuringGraceWindow() async throws {
        // Pin the give-up window so the test exercises "past per-attempt timeout
        // but inside the give-up window" independent of the product default.
        let (service, dataStore) = try await MessageService.createForTesting(
            config: MessageServiceConfig(ackGiveUpWindow: 45)
        )
        let messageID = UUID()

        let message = MessageDTO.testDirectMessage(
            id: messageID,
            radioID: testDeviceID,
            status: .sent
        )
        try await dataStore.saveMessage(message)

        let statusEvents = service.statusEvents()

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
        #expect(fetched?.status == .sent,
                "Grace window must not downgrade to .retrying — nothing is actually retrying")
        #expect(await service.pendingAckCount == 1)

        let events = await service.drainStatusEvents(statusEvents)
        #expect(!events.failedIDs.contains(messageID))
        #expect(events.retryUpdates.isEmpty,
                "Grace window is not a retry; no retrying event should fire")
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

    @Test("checkExpiredAcks does not broadcast failure when DB stays delivered")
    func checkExpiredAcksDoesNotFireHandlerOnDeliveredRow() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let ackCode = Data([0xCE, 0xEC, 0xAC, 0xCE])

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(
                id: messageID,
                radioID: testDeviceID,
                status: .delivered,
                ackCode: ackCode.ackCodeUInt32
            )
        )
        let statusEvents = service.statusEvents()
        await service.setPendingAckForTest(
            makePending(
                messageID: messageID,
                ackCodes: [ackCode],
                sentAt: Date().addingTimeInterval(-60),
                timeout: 30
            )
        )

        try await service.checkExpiredAcks()

        let stored = try await dataStore.fetchMessage(id: messageID)
        #expect(stored?.status == .delivered,
                "DB layer must absorb .delivered against the .failed write")
        let failed = await service.drainStatusEvents(statusEvents).failedIDs
        #expect(!failed.contains(messageID),
                ".failed must not be broadcast when the DB write is a no-op")
    }

    @Test("checkExpiredAcks fails a DM only after ackGiveUpWindow elapses, ignoring the per-attempt timeout")
    func checkExpiredAcksUsesAckGiveUpWindow() async throws {
        let window: TimeInterval = 20
        let (service, dataStore) = try await MessageService.createForTesting(
            config: MessageServiceConfig(ackGiveUpWindow: window)
        )

        let survivingID = UUID()
        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: survivingID, radioID: testDeviceID, status: .sent)
        )
        // Sent inside the window: must stay .sent even though the per-attempt
        // timeout (30s, the makePending default) is already exceeded.
        await service.setPendingAckForTest(
            makePending(
                messageID: survivingID,
                ackCodes: [Data([0x01, 0x02, 0x03, 0x04])],
                sentAt: Date().addingTimeInterval(-(window - 5))
            )
        )

        let expiredID = UUID()
        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: expiredID, radioID: testDeviceID, status: .sent)
        )
        await service.setPendingAckForTest(
            makePending(
                messageID: expiredID,
                ackCodes: [Data([0x05, 0x06, 0x07, 0x08])],
                sentAt: Date().addingTimeInterval(-(window + 5))
            )
        )

        try await service.checkExpiredAcks()

        #expect(try await dataStore.fetchMessage(id: survivingID)?.status == .sent,
                "DM still inside ackGiveUpWindow must not be failed")
        #expect(try await dataStore.fetchMessage(id: expiredID)?.status == .failed,
                "DM past ackGiveUpWindow must be failed")
    }

    @Test("stopAckExpiryChecking leaves in-flight DMs .sent instead of failing them")
    func stopAckExpiryCheckingLeavesPendingSent() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID, radioID: testDeviceID, status: .sent)
        )

        let statusEvents = service.statusEvents()

        await service.setPendingAckForTest(
            makePending(messageID: messageID, ackCodes: [Data([0x09, 0x0A, 0x0B, 0x0C])])
        )
        await service.startAckExpiryChecking()

        await service.stopAckExpiryChecking()

        #expect(await !service.isAckExpiryCheckingActive)
        #expect(try await dataStore.fetchMessage(id: messageID)?.status == .sent,
                "A routine disconnect must not fail in-flight DMs")
        #expect(await service.pendingAckCount == 1,
                "The pending entry must survive so a reconnect ACK can still reconcile")
        #expect(await service.drainStatusEvents(statusEvents).failedIDs.isEmpty)
    }

    // MARK: - failAllPendingMessages

    @Test("failAllPendingMessages fails all non-delivered and broadcasts .failed")
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

        let statusEvents = service.statusEvents()

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

        let failedIDs = await service.drainStatusEvents(statusEvents).failedIDs
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

    @Test("failAllPendingMessages does not downgrade or notify on a delivered DB row")
    func failAllPendingDoesNotDowngradeOrNotifyDelivered() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let ackCode = Data([0xFA, 0x11, 0x77, 0x33])

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(
                id: messageID,
                radioID: testDeviceID,
                status: .delivered,
                ackCode: ackCode.ackCodeUInt32
            )
        )
        let statusEvents = service.statusEvents()
        await service.setPendingAckForTest(
            makePending(messageID: messageID, ackCodes: [ackCode], isDelivered: false)
        )

        try await service.failAllPendingMessages()

        let stored = try await dataStore.fetchMessage(id: messageID)
        #expect(stored?.status == .delivered,
                "failAllPendingMessages must not downgrade a delivered row")
        let failed = await service.drainStatusEvents(statusEvents).failedIDs
        #expect(!failed.contains(messageID),
                ".failed must not be broadcast when the DB write is a no-op")
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
            route: 0,
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

    @Test("finalizeSend broadcasts .statusResolved when in-loop waitForEvent wins the ACK race")
    func finalizeSendFiresAckConfirmationHandler() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let contactID = UUID()
        let radioID = testDeviceID
        let publicKey = Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) })
        let ackCode = Data([0x11, 0x22, 0x33, 0x44])

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(
                id: messageID,
                radioID: radioID,
                contactID: contactID,
                status: .sent
            )
        )

        let statusEvents = service.statusEvents()

        // Seed a non-delivered pendingAcks entry so finalizeSend's `if let sentInfo`
        // branch is taken (isDelivered == false, sentInfo != nil).
        await service.setPendingAckForTest(
            makePending(messageID: messageID, contactID: contactID, ackCodes: [ackCode])
        )

        let sentInfo = MessageSentInfo(
            route: 0,
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

        let confirmedIDs = await service.drainStatusEvents(statusEvents).resolvedIDs
        #expect(confirmedIDs.count == 1,
                ".statusResolved must broadcast exactly once when finalizeSend wins the ACK")
        #expect(confirmedIDs.contains(messageID),
                ".statusResolved must carry the correct message ID")
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

    @Test("ACK within grace window reconciles .sent → .delivered via the in-memory pending entry")
    func ackWithinGraceReconciles() async throws {
        // Pin the give-up window so the late ACK lands inside it regardless of
        // the product default.
        let (service, dataStore) = try await MessageService.createForTesting(
            config: MessageServiceConfig(ackGiveUpWindow: 45)
        )
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
        #expect(afterTimeout?.status == .sent)

        await service.handleAcknowledgement(code: ackCode, tripTime: 99)

        let stored = try await dataStore.fetchMessage(id: messageID)
        #expect(stored?.status == .delivered)
    }

    // MARK: - finalizeSend nil-branch (no premature failure)

    @Test("finalizeSend delivered branch writes .delivered, updates contact, yields .statusResolved, removes entry")
    func finalizeSendDeliveredBranchUnchanged() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let contactID = UUID()
        let radioID = testDeviceID
        let ackCode = Data([0x10, 0x20, 0x30, 0x40])

        try await dataStore.saveContact(ContactDTO.testContact(id: contactID, radioID: radioID, lastMessageDate: nil))
        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID, radioID: radioID, contactID: contactID, status: .sent)
        )
        await service.setPendingAckForTest(
            makePending(messageID: messageID, contactID: contactID, ackCodes: [ackCode])
        )

        let statusEvents = service.statusEvents()
        _ = try await service.finalizeSend(
            messageID: messageID,
            contactID: contactID,
            radioID: radioID,
            publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in 0 }),
            sentInfo: MessageSentInfo(route: 0, expectedAck: ackCode, suggestedTimeoutMs: 5000),
            initialPathLength: 0
        )

        #expect(try await dataStore.fetchMessage(id: messageID)?.status == .delivered)
        #expect(try await dataStore.fetchContact(id: contactID)?.lastMessageDate != nil)
        #expect(await service.pendingAckCount == 0, "delivered branch must take ownership and remove the entry")
        #expect(await service.drainStatusEvents(statusEvents).resolvedIDs == [messageID])
    }

    @Test("finalizeSend nil branch skips the DB write when the listener already delivered")
    func finalizeSendSkipsWhenListenerAlreadyDelivered() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let ackCode = Data([0x55, 0x66, 0x77, 0x88])

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID, radioID: testDeviceID, status: .delivered, ackCode: ackCode.ackCodeUInt32)
        )
        await service.setPendingAckForTest(
            makePending(messageID: messageID, ackCodes: [ackCode], isDelivered: true)
        )

        let statusEvents = service.statusEvents()
        _ = try await service.finalizeSend(
            messageID: messageID,
            contactID: UUID(),
            radioID: testDeviceID,
            publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in 0 }),
            sentInfo: nil,
            initialPathLength: 0
        )

        #expect(try await dataStore.fetchMessage(id: messageID)?.status == .delivered,
                "an already-delivered row must not be downgraded by the nil branch")
        let events = await service.drainStatusEvents(statusEvents)
        #expect(events.failedIDs.isEmpty)
        #expect(events.resolvedIDs.isEmpty, "no status event when the listener already owns delivery")
    }

    @Test("finalizeSend nil branch does not re-stamp the pending entry's sentAt")
    func nilBranchDoesNotReStampSentAt() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let originalSentAt = Date().addingTimeInterval(-12)

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID, radioID: testDeviceID, status: .sent)
        )
        await service.setPendingAckForTest(
            makePending(messageID: messageID, ackCodes: [Data([0x01, 0x02, 0x03, 0x04])], sentAt: originalSentAt, timeout: 30)
        )

        _ = try await service.finalizeSend(
            messageID: messageID,
            contactID: UUID(),
            radioID: testDeviceID,
            publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in 0 }),
            sentInfo: nil,
            initialPathLength: 0
        )

        let entry = await service.pendingAckForTest(messageID)
        #expect(entry != nil, "nil branch must leave the entry for checkExpiredAcks to own the give-up")
        #expect(entry?.sentAt == originalSentAt,
                "re-stamping at give-up would restart the ackGiveUpWindow a second time")
    }

    @Test("finalizeSend nil branch over an already-.failed row leaves it .failed")
    func finalizeSendNilBranchOverAlreadyFailedStaysFailed() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID, radioID: testDeviceID, status: .failed)
        )
        // The live interleave: the checker wrote .failed but the entry is still present.
        await service.setPendingAckForTest(
            makePending(messageID: messageID, ackCodes: [Data([0xAA, 0xBB, 0xCC, 0xDD])], isDelivered: false)
        )

        let statusEvents = service.statusEvents()
        _ = try await service.finalizeSend(
            messageID: messageID,
            contactID: UUID(),
            radioID: testDeviceID,
            publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in 0 }),
            sentInfo: nil,
            initialPathLength: 0
        )

        #expect(try await dataStore.fetchMessage(id: messageID)?.status == .failed,
                "clearRetryingToSent must no-op on a checker-failed row (failed -> sent is FORBIDDEN)")
        let events = await service.drainStatusEvents(statusEvents)
        #expect(events.resolvedIDs.isEmpty, "no .sent yield when clearRetryingToSent returns false")
        #expect(events.failedIDs.isEmpty)
    }

    @Test("finalizeSend nil branch yields .statusResolved(.sent) from a non-terminal row and gates off on a terminal one")
    func nilBranchYieldsStatusResolvedSent() async throws {
        // Part A: a .retrying row moves to .sent and yields exactly one event.
        let (serviceA, storeA) = try await MessageService.createForTesting()
        let idA = UUID()
        try await storeA.saveMessage(
            MessageDTO.testDirectMessage(id: idA, radioID: testDeviceID, status: .retrying)
        )
        await serviceA.setPendingAckForTest(
            makePending(messageID: idA, ackCodes: [Data([0x01, 0x02, 0x03, 0x04])])
        )
        let eventsA = serviceA.statusEvents()
        _ = try await serviceA.finalizeSend(
            messageID: idA,
            contactID: UUID(),
            radioID: testDeviceID,
            publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in 0 }),
            sentInfo: nil,
            initialPathLength: 0
        )
        #expect(try await storeA.fetchMessage(id: idA)?.status == .sent)
        let resolvedA = await serviceA.drainStatusEvents(eventsA).resolvedIDs
        #expect(resolvedA == [idA], "exactly one .statusResolved(.sent) must fire so the bubble leaves 'Retrying'")

        // Part B: an already-.failed row yields nothing and stays .failed.
        let (serviceB, storeB) = try await MessageService.createForTesting()
        let idB = UUID()
        try await storeB.saveMessage(
            MessageDTO.testDirectMessage(id: idB, radioID: testDeviceID, status: .failed)
        )
        await serviceB.setPendingAckForTest(
            makePending(messageID: idB, ackCodes: [Data([0x05, 0x06, 0x07, 0x08])])
        )
        let eventsB = serviceB.statusEvents()
        _ = try await serviceB.finalizeSend(
            messageID: idB,
            contactID: UUID(),
            radioID: testDeviceID,
            publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in 0 }),
            sentInfo: nil,
            initialPathLength: 0
        )
        #expect(try await storeB.fetchMessage(id: idB)?.status == .failed)
        #expect(await serviceB.drainStatusEvents(eventsB).resolvedIDs.isEmpty)
    }

    @Test("late ACK landing in the checker's await-gap cannot flip a just-failed row to .delivered")
    func lateAckDuringCheckerGapStaysFailed() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()
        let contactID = UUID()
        let ackCode = Data([0x9A, 0xBC, 0xDE, 0xF0])

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID, radioID: testDeviceID, contactID: contactID, status: .sent)
        )
        await service.setPendingAckForTest(
            makePending(messageID: messageID, contactID: contactID, ackCodes: [ackCode])
        )

        // Simulate the checker's `.failed` write while leaving the entry in
        // place (the checker is suspended at its cross-actor await before the
        // removeValue).
        _ = try await dataStore.updateMessageStatusUnlessDelivered(id: messageID, status: .failed)

        // A late 0x82 ACK reaches the listener while the entry is still present.
        await service.handleAcknowledgement(code: ackCode, tripTime: 200)

        #expect(try await dataStore.fetchMessage(id: messageID)?.status == .failed,
                "the updateMessageAck terminal guard must refuse .delivered over .failed")
    }

    @Test("late ACK after the row is already .failed with no live entry stays .failed")
    func lateAckAfterFailedStaysFailed() async throws {
        let (service, dataStore) = try await MessageService.createForTesting()
        let messageID = UUID()

        try await dataStore.saveMessage(
            MessageDTO.testDirectMessage(id: messageID, radioID: testDeviceID, status: .failed, ackCode: 0xABCDEF01)
        )

        await service.handleAcknowledgement(code: Data([0x01, 0xEF, 0xCD, 0xAB]), tripTime: 300)

        #expect(try await dataStore.fetchMessage(id: messageID)?.status == .failed,
                "an unmatched late ACK must not resurrect a failed-and-removed row")
        #expect(await service.pendingAckCount == 0)
    }
}
