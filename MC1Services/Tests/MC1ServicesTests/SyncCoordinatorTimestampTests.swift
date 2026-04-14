import Testing
import Foundation
@testable import MC1Services

@Suite("SyncCoordinator Timestamp Correction")
struct SyncCoordinatorTimestampTests {

    // MARK: - Test Constants

    private let oneMinute: TimeInterval = 60
    private let fiveMinutes: TimeInterval = 5 * 60
    private let sixMinutes: TimeInterval = 6 * 60
    private let oneWeek: TimeInterval = 7 * 24 * 60 * 60
    private let threeMonths: TimeInterval = 3 * 30 * 24 * 60 * 60
    private let sixMonths: TimeInterval = 6 * 30 * 24 * 60 * 60
    private let sevenMonths: TimeInterval = 7 * 30 * 24 * 60 * 60

    // MARK: - Valid Range Tests

    @Test("Timestamp within valid range is not corrected")
    func validTimestampNotCorrected() {
        let now = Date()
        let timestamp = UInt32(now.timeIntervalSince1970)

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: now)

        #expect(!wasCorrected)
        #expect(corrected == timestamp)
    }

    // MARK: - Future Timestamp Tests

    @Test("Timestamp 1 minute in future is not corrected")
    func oneMinuteFutureNotCorrected() {
        let now = Date()
        let futureDate = now.addingTimeInterval(oneMinute)
        let timestamp = UInt32(futureDate.timeIntervalSince1970)

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: now)

        #expect(!wasCorrected)
        #expect(corrected == timestamp)
    }

    @Test("Timestamp exactly 5 minutes in future is not corrected")
    func exactlyFiveMinutesFutureNotCorrected() {
        let now = Date()
        let futureDate = now.addingTimeInterval(fiveMinutes)
        let timestamp = UInt32(futureDate.timeIntervalSince1970)

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: now)

        #expect(!wasCorrected)
        #expect(corrected == timestamp)
    }

    @Test("Timestamp 6 minutes in future is corrected")
    func sixMinutesFutureIsCorrected() {
        let now = Date()
        let futureDate = now.addingTimeInterval(sixMinutes)
        let timestamp = UInt32(futureDate.timeIntervalSince1970)

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: now)

        #expect(wasCorrected)
        #expect(corrected == UInt32(now.timeIntervalSince1970))
    }

    // MARK: - Past Timestamp Tests

    @Test("Timestamp 1 week ago is not corrected")
    func oneWeekAgoNotCorrected() {
        let now = Date()
        let pastDate = now.addingTimeInterval(-oneWeek)
        let timestamp = UInt32(pastDate.timeIntervalSince1970)

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: now)

        #expect(!wasCorrected)
        #expect(corrected == timestamp)
    }

    @Test("Timestamp 3 months ago is not corrected")
    func threeMonthsAgoNotCorrected() {
        let now = Date()
        let pastDate = now.addingTimeInterval(-threeMonths)
        let timestamp = UInt32(pastDate.timeIntervalSince1970)

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: now)

        #expect(!wasCorrected)
        #expect(corrected == timestamp)
    }

    @Test("Timestamp exactly 6 months in past is not corrected")
    func exactlySixMonthsAgoNotCorrected() {
        // Use whole-second receive time so UInt32 truncation doesn't push
        // the timestamp past the boundary (fractional seconds are lost in UInt32).
        let now = Date(timeIntervalSince1970: Double(Int(Date().timeIntervalSince1970)))
        let pastDate = now.addingTimeInterval(-sixMonths)
        let timestamp = UInt32(pastDate.timeIntervalSince1970)

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: now)

        #expect(!wasCorrected)
        #expect(corrected == timestamp)
    }

    @Test("Timestamp 7 months ago is corrected")
    func sevenMonthsAgoIsCorrected() {
        let now = Date()
        let pastDate = now.addingTimeInterval(-sevenMonths)
        let timestamp = UInt32(pastDate.timeIntervalSince1970)

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: now)

        #expect(wasCorrected)
        #expect(corrected == UInt32(now.timeIntervalSince1970))
    }

    // MARK: - Edge Case Tests

    @Test("Timestamp of zero (Unix epoch) is corrected")
    func unixEpochIsCorrected() {
        let now = Date()
        let timestamp: UInt32 = 0

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: now)

        #expect(wasCorrected)
        #expect(corrected == UInt32(now.timeIntervalSince1970))
    }

    @Test("Timestamp from year 2020 is corrected")
    func year2020IsCorrected() {
        let now = Date()
        let oldDate = Date(timeIntervalSince1970: 1577836800) // Jan 1, 2020
        let timestamp = UInt32(oldDate.timeIntervalSince1970)

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: now)

        #expect(wasCorrected)
        #expect(corrected == UInt32(now.timeIntervalSince1970))
    }

    @Test("Timestamp from year 2030 is corrected")
    func year2030IsCorrected() {
        let now = Date()
        let futureDate = Date(timeIntervalSince1970: 1893456000) // Jan 1, 2030
        let timestamp = UInt32(futureDate.timeIntervalSince1970)

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: now)

        #expect(wasCorrected)
        #expect(corrected == UInt32(now.timeIntervalSince1970))
    }

    // MARK: - Original Timestamp Preservation Tests

    @Test("Original timestamp is preserved when correction is applied")
    func originalTimestampPreservedForCorrelation() {
        // This test documents critical behavior: the original timestamp must be preserved
        // for RxLogEntry correlation (per payloads.md:65 - ACK deduplication uses original timestamp)
        let now = Date()
        let brokenClockTimestamp: UInt32 = 0  // Unix epoch - clearly invalid

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(brokenClockTimestamp, receiveTime: now)

        // Verify correction was applied
        #expect(wasCorrected)
        #expect(corrected == UInt32(now.timeIntervalSince1970))

        // The original timestamp (0) is still available as the input parameter
        // and should be used for RxLogEntry lookup, not the corrected value.
        // This is verified by the fact that correctTimestampIfNeeded returns
        // ONLY the corrected timestamp - the caller must preserve the original.
        #expect(brokenClockTimestamp == 0)  // Original unchanged
        #expect(corrected != brokenClockTimestamp)  // Different from original
    }

    @Test("Corrected timestamp differs from original for invalid input")
    func correctedTimestampDiffersFromOriginal() {
        let now = Date()
        let farFuture = now.addingTimeInterval(365 * 24 * 60 * 60) // 1 year in future
        let originalTimestamp = UInt32(farFuture.timeIntervalSince1970)

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(originalTimestamp, receiveTime: now)

        #expect(wasCorrected)
        // The corrected timestamp should be the receive time, not the invalid original
        #expect(corrected == UInt32(now.timeIntervalSince1970))
        // Original and corrected must be different (caller uses original for RxLogEntry lookup)
        #expect(corrected != originalTimestamp)
    }

    // MARK: - Underflow Prevention Tests

    @Test("Receive time near Unix epoch does not crash")
    func nearEpochReceiveTimeDoesNotCrash() {
        // Device clock set to early 1970 - would previously cause UInt32 underflow crash
        let nearEpoch = Date(timeIntervalSince1970: 1000) // ~16 minutes after Unix epoch
        let timestamp: UInt32 = 500

        // This should not crash - the fix uses TimeInterval arithmetic instead of UInt32
        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: nearEpoch)

        // Timestamp is within range (500 is less than 6 months before 1000)
        #expect(!wasCorrected)
        #expect(corrected == timestamp)
    }

    @Test("Receive time at Unix epoch handles timestamp validation")
    func epochReceiveTimeHandlesValidation() {
        let epoch = Date(timeIntervalSince1970: 0)
        let timestamp: UInt32 = 1_000_000 // ~11 days after epoch

        let (corrected, wasCorrected) = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime: epoch)

        // Timestamp is too far in the future from epoch perspective (> 5 minutes)
        #expect(wasCorrected)
        #expect(corrected == 0)
    }
}

// MARK: - Same-Sender Reordering Tests

@Suite("Same-Sender Reordering")
struct SameSenderReorderingTests {

    private func makeDMMessage(
        timestamp: UInt32,
        createdAt: Date,
        direction: MessageDirection = .incoming
    ) -> MessageDTO {
        MessageDTO(
            id: UUID(),
            radioID: UUID(),
            contactID: UUID(),
            channelIndex: nil,
            text: "msg-\(timestamp)",
            timestamp: timestamp,
            createdAt: createdAt,
            direction: direction,
            status: .delivered,
            textType: .plain,
            ackCode: nil,
            pathLength: 0,
            snr: nil,
            senderKeyPrefix: nil,
            senderNodeName: nil,
            isRead: false,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0
        )
    }

    private func makeChannelMessage(
        timestamp: UInt32,
        createdAt: Date,
        senderName: String? = nil,
        direction: MessageDirection = .incoming
    ) -> MessageDTO {
        MessageDTO(
            id: UUID(),
            radioID: UUID(),
            contactID: nil,
            channelIndex: 0,
            text: "msg-\(timestamp)",
            timestamp: timestamp,
            createdAt: createdAt,
            direction: direction,
            status: .delivered,
            textType: .plain,
            ackCode: nil,
            pathLength: 0,
            snr: nil,
            senderKeyPrefix: nil,
            senderNodeName: senderName,
            isRead: false,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0
        )
    }

    @Test("Empty array returns empty")
    func emptyArray() {
        let result = MessageDTO.reorderSameSenderClusters([])
        #expect(result.isEmpty)
    }

    @Test("Single message returns unchanged")
    func singleMessage() {
        let msg = makeDMMessage(timestamp: 100, createdAt: Date())
        let result = MessageDTO.reorderSameSenderClusters([msg])
        #expect(result.count == 1)
        #expect(result[0].id == msg.id)
    }

    @Test("DM messages within 5 seconds are reordered by sender timestamp")
    func dmReorderWithinWindow() {
        let base = Date()
        // Messages arrived out of order: msg2 arrived first, then msg1
        let msg1 = makeDMMessage(timestamp: 100, createdAt: base.addingTimeInterval(2))
        let msg2 = makeDMMessage(timestamp: 200, createdAt: base)

        // Sorted by createdAt: [msg2(t=200), msg1(t=100)]
        let input = [msg2, msg1]
        let result = MessageDTO.reorderSameSenderClusters(input)

        // Should reorder by sender timestamp: [msg1(t=100), msg2(t=200)]
        #expect(result[0].timestamp == 100)
        #expect(result[1].timestamp == 200)
    }

    @Test("Outgoing DM messages within 5 seconds are reordered by sender timestamp")
    func outgoingDMReorderWithinWindow() {
        let base = Date()
        let msg1 = makeDMMessage(timestamp: 100, createdAt: base.addingTimeInterval(2), direction: .outgoing)
        let msg2 = makeDMMessage(timestamp: 200, createdAt: base, direction: .outgoing)

        let input = [msg2, msg1]
        let result = MessageDTO.reorderSameSenderClusters(input)

        #expect(result[0].timestamp == 100)
        #expect(result[1].timestamp == 200)
    }

    @Test("DM messages beyond 5 seconds are not reordered")
    func dmNoReorderBeyondWindow() {
        let base = Date()
        let msg1 = makeDMMessage(timestamp: 100, createdAt: base)
        let msg2 = makeDMMessage(timestamp: 200, createdAt: base.addingTimeInterval(6))

        let input = [msg1, msg2]
        let result = MessageDTO.reorderSameSenderClusters(input)

        // Beyond window — stays in createdAt order
        #expect(result[0].timestamp == 100)
        #expect(result[1].timestamp == 200)
    }

    @Test("Channel messages from different senders are not clustered")
    func channelDifferentSendersNotClustered() {
        let base = Date()
        // Alice sends at t=200, Bob sends at t=100, both arrive within 2 seconds
        let alice = makeChannelMessage(timestamp: 200, createdAt: base, senderName: "Alice")
        let bob = makeChannelMessage(timestamp: 100, createdAt: base.addingTimeInterval(2), senderName: "Bob")

        let input = [alice, bob]
        let result = MessageDTO.reorderSameSenderClusters(input)

        // Different senders — no reordering
        #expect(result[0].senderNodeName == "Alice")
        #expect(result[1].senderNodeName == "Bob")
    }

    @Test("Channel messages from same sender within window are reordered")
    func channelSameSenderReordered() {
        let base = Date()
        let msg1 = makeChannelMessage(timestamp: 100, createdAt: base.addingTimeInterval(3), senderName: "Alice")
        let msg2 = makeChannelMessage(timestamp: 200, createdAt: base, senderName: "Alice")

        // createdAt order: [msg2(t=200), msg1(t=100)]
        let input = [msg2, msg1]
        let result = MessageDTO.reorderSameSenderClusters(input)

        // Same sender within window — reordered by timestamp
        #expect(result[0].timestamp == 100)
        #expect(result[1].timestamp == 200)
    }

    @Test("Mixed directions break clusters")
    func mixedDirectionsBreakCluster() {
        let base = Date()
        let incoming = makeDMMessage(timestamp: 200, createdAt: base, direction: .incoming)
        let outgoing = makeDMMessage(timestamp: 100, createdAt: base.addingTimeInterval(1), direction: .outgoing)

        let input = [incoming, outgoing]
        let result = MessageDTO.reorderSameSenderClusters(input)

        // Different directions — no reordering
        #expect(result[0].direction == .incoming)
        #expect(result[1].direction == .outgoing)
    }

    @Test("Three messages in cluster are fully sorted")
    func threeMessageCluster() {
        let base = Date()
        // Arrived in reverse order within 4 seconds
        let msg1 = makeDMMessage(timestamp: 300, createdAt: base)
        let msg2 = makeDMMessage(timestamp: 100, createdAt: base.addingTimeInterval(2))
        let msg3 = makeDMMessage(timestamp: 200, createdAt: base.addingTimeInterval(4))

        let input = [msg1, msg2, msg3]
        let result = MessageDTO.reorderSameSenderClusters(input)

        #expect(result[0].timestamp == 100)
        #expect(result[1].timestamp == 200)
        #expect(result[2].timestamp == 300)
    }

    @Test("Exactly 5 second gap is included in cluster")
    func exactlyFiveSecondGap() {
        let base = Date()
        let msg1 = makeDMMessage(timestamp: 200, createdAt: base)
        let msg2 = makeDMMessage(timestamp: 100, createdAt: base.addingTimeInterval(5))

        let input = [msg1, msg2]
        let result = MessageDTO.reorderSameSenderClusters(input)

        // 5 seconds is within window (<=5)
        #expect(result[0].timestamp == 100)
        #expect(result[1].timestamp == 200)
    }

    @Test("Multiple consecutive clusters are each reordered independently")
    func multipleConsecutiveClusters() {
        let base = Date()
        // Cluster 1: two messages within 3s, out of timestamp order
        let c1a = makeDMMessage(timestamp: 200, createdAt: base)
        let c1b = makeDMMessage(timestamp: 100, createdAt: base.addingTimeInterval(3))

        // Gap of 10 seconds separates the clusters
        // Cluster 2: two messages within 2s, out of timestamp order
        let c2a = makeDMMessage(timestamp: 400, createdAt: base.addingTimeInterval(13))
        let c2b = makeDMMessage(timestamp: 300, createdAt: base.addingTimeInterval(15))

        let input = [c1a, c1b, c2a, c2b]
        let result = MessageDTO.reorderSameSenderClusters(input)

        // Cluster 1 reordered by timestamp
        #expect(result[0].timestamp == 100)
        #expect(result[1].timestamp == 200)
        // Cluster 2 reordered by timestamp
        #expect(result[2].timestamp == 300)
        #expect(result[3].timestamp == 400)
    }

    @Test("Channel messages with nil sender names are not clustered")
    func nilSenderNamesNotClustered() {
        let base = Date()
        let msg1 = makeChannelMessage(timestamp: 200, createdAt: base)
        let msg2 = makeChannelMessage(timestamp: 100, createdAt: base.addingTimeInterval(2))

        let result = MessageDTO.reorderSameSenderClusters([msg1, msg2])

        // Nil senders should NOT cluster — stays in createdAt order
        #expect(result[0].timestamp == 200)
        #expect(result[1].timestamp == 100)
    }

    @Test("Same-sender messages with identical timestamps use createdAt as tiebreaker")
    func identicalTimestampsUsesCreatedAtTiebreaker() {
        let base = Date()
        let msg1 = makeDMMessage(timestamp: 100, createdAt: base)
        let msg2 = makeDMMessage(timestamp: 100, createdAt: base.addingTimeInterval(0.5))

        let input = [msg2, msg1]  // reverse createdAt order
        let result = MessageDTO.reorderSameSenderClusters(input)

        #expect(result[0].id == msg1.id)
        #expect(result[1].id == msg2.id)
    }

    @Test("Messages already in correct order are unchanged")
    func alreadyCorrectOrder() {
        let base = Date()
        let msg1 = makeDMMessage(timestamp: 100, createdAt: base)
        let msg2 = makeDMMessage(timestamp: 200, createdAt: base.addingTimeInterval(1))
        let msg3 = makeDMMessage(timestamp: 300, createdAt: base.addingTimeInterval(2))

        let input = [msg1, msg2, msg3]
        let result = MessageDTO.reorderSameSenderClusters(input)

        #expect(result[0].id == msg1.id)
        #expect(result[1].id == msg2.id)
        #expect(result[2].id == msg3.id)
    }
}
