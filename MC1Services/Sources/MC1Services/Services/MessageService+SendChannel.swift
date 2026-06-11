import Foundation
import MeshCore

extension MessageService {

    // MARK: - Send Channel Message

    /// Sends a broadcast message to a channel.
    ///
    /// Channel messages are broadcast to all devices listening on the specified channel.
    /// No acknowledgement is expected or tracked for channel messages.
    ///
    /// - Parameters:
    ///   - text: The message text to broadcast. Total payload, including the local
    ///     node-name header used by repeaters, is bounded by
    ///     `ProtocolLimits.maxChannelMessageTotalLength` (147 UTF-8 bytes).
    ///   - channelIndex: The channel index (0-7)
    ///   - radioID: The local device ID
    ///   - textType: The text encoding type (defaults to `.plain`)
    ///
    /// - Returns: The ID of the created message
    ///
    /// - Throws:
    ///   - `MessageServiceError.messageTooLong` if text exceeds `ProtocolLimits.maxChannelMessageTotalLength`
    ///   - `MessageServiceError.channelNotFound` if channel index is invalid
    ///   - `MessageServiceError.sessionError` if MeshCore send fails
    ///
    /// # Example
    ///
    /// ```swift
    /// let messageID = try await messageService.sendChannelMessage(
    ///     text: "Hello channel!",
    ///     channelIndex: 0,
    ///     radioID: device.id
    /// )
    /// ```
    public func sendChannelMessage(
        text: String,
        channelIndex: UInt8,
        radioID: UUID,
        textType: TextType = .plain
    ) async throws -> (id: UUID, timestamp: UInt32) {
        // Validate message length (byte count matches firmware buffer limits)
        guard text.utf8.count <= ProtocolLimits.maxChannelMessageTotalLength else {
            throw MessageServiceError.messageTooLong
        }

        let messageID = UUID()
        let timestamp = UInt32(Date().timeIntervalSince1970)

        // Save message to store as pending first
        let messageDTO = createOutgoingChannelMessage(
            id: messageID,
            radioID: radioID,
            channelIndex: channelIndex,
            text: text,
            timestamp: timestamp,
            textType: textType
        )
        try await dataStore.saveMessage(messageDTO)

        do {
            try await withPoolBackoff(transientCode: FirmwareDeviceErrorCode.channelMessageNotFound, config: config.poolBackoff, logger: logger) {
                try await session.sendChannelMessage(
                    channel: channelIndex,
                    text: text,
                    timestamp: Date(timeIntervalSince1970: TimeInterval(timestamp))
                )
            }
        } catch {
            statusEventBroadcaster.yield(.failed(messageID: messageID))
            try await failMessageAndRethrow(error, messageID: messageID)
        }

        // Post-broadcast bookkeeping. The broadcast already left the radio,
        // so a throw here must not mark `.failed` — that would lie about
        // delivery. Log and continue; the message stays `.sent` (or sticks
        // `.pending` if `updateMessageStatus` itself threw, which a subsequent
        // ack or user retry can reconverge). No queue row exists on this
        // inline path, so the "retry button hidden via missing PendingSend"
        // pathology from the queue-routed path does not apply.
        do {
            try await dataStore.updateMessageStatus(id: messageID, status: .sent)
            statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .sent, roundTripTime: nil))
            if let channel = try await dataStore.fetchChannel(radioID: radioID, index: channelIndex) {
                try await dataStore.updateChannelLastMessage(channelID: channel.id, date: Date())
            }
        } catch {
            logger.warning("Channel post-broadcast bookkeeping failed (inline path) messageID=\(messageID) broadcast already out: \(String(describing: error))")
        }

        return (id: messageID, timestamp: timestamp)
    }

    /// Creates a pending channel message without sending it.
    ///
    /// Use this for optimistic UI — the message is saved immediately and can be
    /// displayed in the conversation while the actual send happens in the background
    /// via ``sendPendingChannelMessage(messageID:channelIndex:radioID:)``.
    ///
    /// - Parameters:
    ///   - text: The message text
    ///   - channelIndex: The channel index to send on
    ///   - radioID: The device ID
    ///   - textType: The text type (defaults to `.plain`)
    ///
    /// - Returns: The created message DTO with pending status
    public func createPendingChannelMessage(
        text: String,
        channelIndex: UInt8,
        radioID: UUID,
        textType: TextType = .plain
    ) async throws -> MessageDTO {
        guard text.utf8.count <= ProtocolLimits.maxChannelMessageTotalLength else {
            throw MessageServiceError.messageTooLong
        }

        let messageID = UUID()
        let timestamp = UInt32(Date().timeIntervalSince1970)

        let messageDTO = createOutgoingChannelMessage(
            id: messageID,
            radioID: radioID,
            channelIndex: channelIndex,
            text: text,
            timestamp: timestamp,
            textType: textType
        )
        try await dataStore.saveMessage(messageDTO)

        return messageDTO
    }

    /// Sends an already-created pending channel message.
    ///
    /// Use this after ``createPendingChannelMessage(text:channelIndex:radioID:textType:)``
    /// to transmit the message over the mesh. Updates the message status to `.sent` on
    /// success or `.failed` on error.
    ///
    /// - Parameter messageID: The ID of the pending message to send
    public func sendPendingChannelMessage(messageID: UUID) async throws {
        // Catch 1: validation guards + send + status flip. Validation lives
        // inside the do/catch so a missing message or missing channel index
        // consistently writes `.failed` to the DB before propagating — paired
        // with the queue's outer catch, which fires `notifyMessageFailed`.
        // A failure before .sent is committed means the user should see
        // .failed (retry button visible).
        let radioID: UUID
        let channelIndex: UInt8
        do {
            guard let message = try await dataStore.fetchMessage(id: messageID) else {
                throw MessageServiceError.sendFailed("Message not found")
            }
            guard let idx = message.channelIndex else {
                throw MessageServiceError.sendFailed("Not a channel message")
            }
            radioID = message.radioID
            channelIndex = idx

            try await withPoolBackoff(transientCode: FirmwareDeviceErrorCode.channelMessageNotFound, config: config.poolBackoff, logger: logger) {
                try await session.sendChannelMessage(
                    channel: channelIndex,
                    text: message.text,
                    timestamp: Date(timeIntervalSince1970: TimeInterval(message.timestamp))
                )
            }
            try await dataStore.updateMessageStatus(id: messageID, status: .sent)
        } catch {
            try await failMessageAndRethrow(error, messageID: messageID)
        }

        // Catch 2: post-status bookkeeping. Status is already .sent in DB; the
        // radio broadcast happened. A failure here is logged but does not mark
        // .failed — that would corrupt the user-visible delivery state. The
        // bookkeeping (channel last-message timestamp, sent event) is
        // best-effort metadata that next-load or next-ack will reconverge.
        do {
            statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .sent, roundTripTime: nil))
            if let channel = try await dataStore.fetchChannel(radioID: radioID, index: channelIndex) {
                try await dataStore.updateChannelLastMessage(channelID: channel.id, date: Date())
            }
        } catch {
            logger.warning("Channel post-status bookkeeping failed messageID=\(messageID) status=.sent already committed: \(String(describing: error))")
        }
    }

    /// Resend an existing channel message, incrementing its send count.
    ///
    /// This is used for "Send Again" - it re-transmits the same message
    /// rather than creating a duplicate. Uses a new timestamp so the mesh
    /// treats it as a fresh broadcast.
    ///
    /// - Returns: The new wire timestamp written to the message row.
    ///   Reaction indexing keys off this value via
    ///   `SHA256(text || timestamp.littleEndian)`; callers that index reactions
    ///   for the resent packet must pass this value rather than the pre-resend
    ///   timestamp from the queued envelope.
    @discardableResult
    public func resendChannelMessage(
        messageID: UUID,
        preserveTimestamp: Bool = false
    ) async throws -> UInt32 {
        // Broadcast .resent only after .sent is committed. The sentCommitted
        // flag suppresses a spurious broadcast if catch 1 rethrows;
        // failMessageAndRethrow always re-throws, so a catch-1 failure exits
        // the function before the broadcast site is reached and the guard is
        // belt-and-braces. The event still fires when catch 2 bookkeeping
        // throws, since sentCommitted is set before catch 2 runs. Yielding
        // after catch 2 and before return preserves the committed-before-
        // broadcast ordering the resend tests assert.
        var sentCommitted = false

        // Catch 1: validation guards + timestamp update + send + status flip.
        // Validation lives inside the do/catch so a missing message or missing
        // channel index consistently writes `.failed` before propagating —
        // paired with the queue's outer catch. Once `.sent` is committed, the
        // per-attempt bookkeeping below cannot retroactively flip it.
        let wireTimestamp: UInt32
        do {
            guard let message = try await dataStore.fetchMessage(id: messageID) else {
                throw MessageServiceError.sendFailed("Message not found")
            }
            guard let channelIndex = message.channelIndex else {
                throw MessageServiceError.sendFailed("Not a channel message")
            }

            let wireDate: Date
            if preserveTimestamp {
                wireTimestamp = message.timestamp
                wireDate = Date(timeIntervalSince1970: TimeInterval(wireTimestamp))
            } else {
                let now = Date()
                wireTimestamp = UInt32(now.timeIntervalSince1970)
                wireDate = now
            }

            if !preserveTimestamp {
                try await dataStore.updateMessageTimestamp(id: messageID, timestamp: wireTimestamp)
            }
            try await withPoolBackoff(transientCode: FirmwareDeviceErrorCode.channelMessageNotFound, config: config.poolBackoff, logger: logger) {
                try await session.sendChannelMessage(
                    channel: channelIndex,
                    text: message.text,
                    timestamp: wireDate
                )
            }
            try await dataStore.updateMessageStatus(id: messageID, status: .sent)
            sentCommitted = true
        } catch {
            try await failMessageAndRethrow(error, messageID: messageID)
        }

        // Catch 2: per-attempt bookkeeping. Status is already .sent in DB and
        // the radio broadcast happened. Failures here log only — the message
        // is correctly marked .sent and the missing metadata (sendCount,
        // heardRepeats clear) is non-load-bearing for delivery semantics; the
        // next inbound ack/repeat event will reconverge.
        do {
            _ = try await dataStore.incrementMessageSendCount(id: messageID)
            try await dataStore.updateMessageHeardRepeats(id: messageID, heardRepeats: 0)
            try await dataStore.deleteMessageRepeats(messageID: messageID)
        } catch {
            logger.warning("Resend post-status bookkeeping failed messageID=\(messageID) status=.sent already committed: \(String(describing: error))")
        }

        if sentCommitted {
            statusEventBroadcaster.yield(.resent(messageID: messageID))
        }

        return wireTimestamp
    }

    private func createOutgoingChannelMessage(
        id: UUID,
        radioID: UUID,
        channelIndex: UInt8,
        text: String,
        timestamp: UInt32,
        textType: TextType
    ) -> MessageDTO {
        let message = Message(
            id: id,
            radioID: radioID,
            channelIndex: channelIndex,
            text: text,
            timestamp: timestamp,
            directionRawValue: MessageDirection.outgoing.rawValue,
            statusRawValue: MessageStatus.pending.rawValue,
            textTypeRawValue: textType.rawValue
        )
        return MessageDTO(from: message)
    }
}
