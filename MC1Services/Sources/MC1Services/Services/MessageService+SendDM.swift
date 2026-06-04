import Foundation
import MeshCore

// MARK: - Send Direct Message

extension MessageService {

    /// Sends a direct message to a contact with a single send attempt.
    ///
    /// This method sends a message once without automatic retry. Use this when you want
    /// to manually control retry logic or when retry is not needed.
    ///
    /// - Parameters:
    ///   - text: The message text to send. Bounded by
    ///     `ProtocolLimits.maxDirectMessageLength` (150 UTF-8 bytes).
    ///   - contact: The recipient contact
    ///   - textType: The text encoding type (defaults to `.plain`)
    ///   - replyToID: Optional ID of message being replied to
    ///
    /// - Returns: The created message DTO with pending/sent status
    ///
    /// - Throws:
    ///   - `MessageServiceError.invalidRecipient` if contact is a repeater
    ///   - `MessageServiceError.messageTooLong` if text exceeds `ProtocolLimits.maxDirectMessageLength`
    ///   - `MessageServiceError.sessionError` if MeshCore send fails
    ///
    /// # Example
    ///
    /// ```swift
    /// let message = try await messageService.sendDirectMessage(
    ///     text: "Hello!",
    ///     to: contact
    /// )
    /// ```
    public func sendDirectMessage(
        text: String,
        to contact: ContactDTO,
        textType: TextType = .plain,
        replyToID: UUID? = nil
    ) async throws -> MessageDTO {
        try validateDirectMessage(text: text, to: contact)

        let messageID = UUID()
        let timestamp = UInt32(Date().timeIntervalSince1970)

        let messageDTO = createOutgoingMessage(
            id: messageID,
            radioID: contact.radioID,
            contactID: contact.id,
            text: text,
            timestamp: timestamp,
            textType: textType,
            replyToID: replyToID
        )
        try await dataStore.saveMessage(messageDTO)

        do {
            // Precompute the expected ACK before the send so the persistent
            // listener cannot race trackPendingAck on short direct links (same
            // pattern used by the retry loop). Reactions and quick replies
            // flow through this path and otherwise lose ACKs on fast links.
            guard let senderPublicKey = await session.currentSelfInfo?.publicKey else {
                throw MessageServiceError.notConnected
            }
            let predictedAck = AckCodeBuilder.expectedAck(
                timestamp: timestamp,
                attempt: 0,
                text: text,
                senderPublicKey: senderPublicKey
            )
            // Pre-send floor must outlive one checkExpiredAcks tick, otherwise
            // a slow BLE round-trip could let the checker expire the
            // speculative entry before the authoritative timeout lands.
            trackPendingAck(
                messageID: messageID,
                contactID: contact.id,
                ackCode: predictedAck,
                timeout: checkInterval
            )

            let sentInfo: MessageSentInfo
            do {
                sentInfo = try await withPoolBackoff(transientCode: FirmwareDeviceErrorCode.directMessageTableFull, config: config.poolBackoff, logger: logger) {
                    try await session.sendMessage(
                        to: contact.publicKey,
                        text: text,
                        timestamp: Date(timeIntervalSince1970: TimeInterval(timestamp))
                    )
                }
            } catch {
                pendingAcks.removeValue(forKey: messageID)
                throw error
            }

            let ackTimeout = TimeInterval(sentInfo.suggestedTimeoutMs) / 1000.0 * 1.2

            if sentInfo.expectedAck != predictedAck {
                logger.warning(
                    "expectedAck mismatch for \(messageID) attempt 0: predicted \(predictedAck.hexString()) vs firmware \(sentInfo.expectedAck.hexString()); merging firmware code"
                )
                trackPendingAck(
                    messageID: messageID,
                    contactID: contact.id,
                    ackCode: sentInfo.expectedAck,
                    timeout: ackTimeout
                )
            } else {
                pendingAcks[messageID]?.timeout = ackTimeout
                pendingAcks[messageID]?.sentAt = Date()
            }

            // updateMessageAck preserves `.delivered`, so writing `.sent` here
            // is a no-op when the listener already won the race.
            let ackCodeUInt32 = sentInfo.expectedAck.ackCodeUInt32
            try await dataStore.updateMessageAck(
                id: messageID,
                ackCode: ackCodeUInt32,
                status: .sent
            )

            try await dataStore.updateContactLastMessage(contactID: contact.id, date: Date())
            await messageSentHandler?(messageID, .sent, nil)

            guard let message = try await dataStore.fetchMessage(id: messageID) else {
                throw MessageServiceError.sendFailed("Failed to fetch saved message")
            }
            return message
        } catch {
            await messageFailedHandler?(messageID)
            try await failMessageAndRethrow(error, messageID: messageID)
        }
    }

    // MARK: - Send with Automatic Retry

    /// Sends a direct message with automatic retry and flood routing fallback.
    ///
    /// This is the recommended method for sending messages. It automatically:
    /// 1. Attempts direct routing up to `maxAttempts` times
    /// 2. Switches to flood routing after `floodAfter` attempts
    /// 3. Makes up to `maxFloodAttempts` using flood routing
    /// 4. Returns immediately when ACK is received
    ///
    /// The message is saved to the database immediately and the `onMessageCreated`
    /// callback is invoked, allowing the UI to update before the send completes.
    ///
    /// - Parameters:
    ///   - text: The message text to send. Bounded by
    ///     `ProtocolLimits.maxDirectMessageLength` (150 UTF-8 bytes).
    ///   - contact: The recipient contact
    ///   - textType: The text encoding type (defaults to `.plain`)
    ///   - replyToID: Optional ID of message being replied to
    ///   - timeout: Custom timeout in seconds (0 = use device-suggested timeout)
    ///   - onMessageCreated: Callback invoked after message is saved to database
    ///
    /// - Returns: The message DTO with final delivery status (delivered or failed)
    ///
    /// - Throws:
    ///   - `MessageServiceError.invalidRecipient` if contact is a repeater
    ///   - `MessageServiceError.messageTooLong` if text exceeds `ProtocolLimits.maxDirectMessageLength`
    ///   - `MessageServiceError.sessionError` if MeshCore send fails
    ///
    /// # Example
    ///
    /// ```swift
    /// let message = try await messageService.sendMessageWithRetry(
    ///     text: "Hello!",
    ///     to: contact
    /// ) { savedMessage in
    ///     // Update UI immediately with pending message
    ///     await updateConversation(with: savedMessage)
    /// }
    /// // Message is now delivered or failed
    /// ```
    public func sendMessageWithRetry(
        text: String,
        to contact: ContactDTO,
        textType: TextType = .plain,
        replyToID: UUID? = nil,
        timeout: TimeInterval = 0,
        onMessageCreated: (@Sendable (MessageDTO) async -> Void)? = nil
    ) async throws -> MessageDTO {
        try validateDirectMessage(text: text, to: contact)

        let messageID = UUID()
        let timestamp = UInt32(Date().timeIntervalSince1970)

        // Save message to store as pending first
        let messageDTO = createOutgoingMessage(
            id: messageID,
            radioID: contact.radioID,
            contactID: contact.id,
            text: text,
            timestamp: timestamp,
            textType: textType,
            replyToID: replyToID
        )
        try await dataStore.saveMessage(messageDTO)

        // Notify caller that message is saved
        await onMessageCreated?(messageDTO)

        // Capture initial routing state to detect changes
        let initialPathLength = contact.outPathLength

        // Run app-layer retry loop with UI notifications
        do {
            let sentInfo = try await sendDirectMessageWithRetryLoop(
                messageID: messageID,
                contactID: contact.id,
                radioID: contact.radioID,
                publicKey: contact.publicKey,
                text: text,
                timestamp: Date(timeIntervalSince1970: TimeInterval(timestamp)),
                timestampRaw: timestamp,
                timeout: timeout > 0 ? timeout : nil
            )

            return try await finalizeSend(
                messageID: messageID,
                contactID: contact.id,
                radioID: contact.radioID,
                publicKey: contact.publicKey,
                sentInfo: sentInfo,
                initialPathLength: initialPathLength
            )
        } catch {
            await messageFailedHandler?(messageID)
            try await failMessageAndRethrow(error, messageID: messageID)
        }
    }

    /// Creates a pending message without sending it.
    ///
    /// Use this when you want to show the message in the UI immediately
    /// and drain it later via ``sendPendingDirectMessage(messageID:to:preserveTimestamp:)``.
    ///
    /// - Parameters:
    ///   - text: The message text
    ///   - contact: The recipient contact
    ///   - textType: The type of text content (default: .plain)
    ///   - replyToID: Optional ID of message being replied to
    ///
    /// - Returns: The created message DTO with pending status
    public func createPendingMessage(
        text: String,
        to contact: ContactDTO,
        textType: TextType = .plain,
        replyToID: UUID? = nil
    ) async throws -> MessageDTO {
        try validateDirectMessage(text: text, to: contact)

        let messageID = UUID()
        let timestamp = UInt32(Date().timeIntervalSince1970)

        let messageDTO = createOutgoingMessage(
            id: messageID,
            radioID: contact.radioID,
            contactID: contact.id,
            text: text,
            timestamp: timestamp,
            textType: textType,
            replyToID: replyToID
        )
        try await dataStore.saveMessage(messageDTO)
        try await dataStore.updateContactLastMessage(contactID: contact.id, date: Date())

        return messageDTO
    }

    /// Drains a `.pending` DM through the app-layer retry loop. Does not
    /// bump `sendCount` or fire `messageResentHandler` — both bubble-
    /// affecting side effects belong to
    /// ``resendDirectMessage(messageID:to:preserveTimestamp:)``.
    ///
    /// - Parameter preserveTimestamp: True when a prior drain attempt may
    ///   already have placed the packet on the wire (queue auto-park-and-
    ///   retry), so the recipient's dedup ring catches the duplicate
    ///   rather than rendering it twice. False on the first drain, where
    ///   a fresh timestamp keeps mesh repeaters from filtering the packet.
    public func sendPendingDirectMessage(
        messageID: UUID,
        to contact: ContactDTO,
        preserveTimestamp: Bool = false
    ) async throws -> MessageDTO {
        try await performQueuedDirectMessageSend(
            messageID: messageID,
            to: contact,
            preserveTimestamp: preserveTimestamp,
            isResend: false
        )
    }

    /// Resends an already-sent DM. Re-runs the app-layer retry loop,
    /// increments `sendCount`, and fires `messageResentHandler` so the
    /// bubble surfaces "Sent N times".
    ///
    /// - Parameter preserveTimestamp: True on queue auto-park-and-retry
    ///   of the resend; false on the first drain attempt so the wire
    ///   packet hashes distinctly from the original and clears repeater
    ///   dedup rings.
    public func resendDirectMessage(
        messageID: UUID,
        to contact: ContactDTO,
        preserveTimestamp: Bool = false
    ) async throws -> MessageDTO {
        try await performQueuedDirectMessageSend(
            messageID: messageID,
            to: contact,
            preserveTimestamp: preserveTimestamp,
            isResend: true
        )
    }

    private func performQueuedDirectMessageSend(
        messageID: UUID,
        to contact: ContactDTO,
        preserveTimestamp: Bool,
        isResend: Bool
    ) async throws -> MessageDTO {
        guard !inFlightRetries.contains(messageID) else {
            logger.warning("Send already in progress for message: \(messageID)")
            throw MessageServiceError.sendFailed("Retry already in progress")
        }

        inFlightRetries.insert(messageID)
        defer { inFlightRetries.remove(messageID) }

        let initialPathLength = contact.outPathLength

        guard let existingMessage = try await dataStore.fetchMessage(id: messageID) else {
            throw MessageServiceError.sendFailed("Message not found")
        }

        // Fresh timestamp on first drain — mesh repeaters deduplicate by
        // packet content, so reusing the original would be dropped. Queue
        // auto-retry passes preserveTimestamp: true so the duplicate landing
        // on a recipient that already saw the first send gets caught by
        // their dedup ring rather than rendered as a second copy.
        let wireTimestamp: Date
        let wireTimestampRaw: UInt32
        if preserveTimestamp {
            wireTimestampRaw = existingMessage.timestamp
            wireTimestamp = Date(timeIntervalSince1970: TimeInterval(wireTimestampRaw))
        } else {
            wireTimestamp = Date()
            wireTimestampRaw = UInt32(wireTimestamp.timeIntervalSince1970)
            try await dataStore.updateMessageTimestamp(id: messageID, timestamp: wireTimestampRaw)
        }

        do {
            let sentInfo = try await sendDirectMessageWithRetryLoop(
                messageID: messageID,
                contactID: contact.id,
                radioID: contact.radioID,
                publicKey: contact.publicKey,
                text: existingMessage.text,
                timestamp: wireTimestamp,
                timestampRaw: wireTimestampRaw,
                timeout: nil
            )

            // Bump only on a confirmed successful resend so sendCount counts
            // landed user-initiated sends, not queue-driven attempts;
            // bookkeeping failures log because the on-air retransmission
            // already happened.
            if isResend, sentInfo != nil {
                do {
                    _ = try await dataStore.incrementMessageSendCount(id: messageID)
                } catch {
                    logger.warning("DM resend sendCount bookkeeping failed messageID=\(messageID): \(String(describing: error))")
                }
            }

            let message = try await finalizeSend(
                messageID: messageID,
                contactID: contact.id,
                radioID: contact.radioID,
                publicKey: contact.publicKey,
                sentInfo: sentInfo,
                initialPathLength: initialPathLength
            )

            // Fire after the DB write so the downstream refetch sees both
            // the bumped sendCount and the committed terminal status.
            if isResend, sentInfo != nil {
                await messageResentHandler?(messageID)
            }

            return message
        } catch {
            try await failMessageAndRethrow(error, messageID: messageID)
        }
    }

    // MARK: - Direct Message Retry Loop

    /// Sends a direct message with app-layer retry logic and UI notifications.
    ///
    /// This function manages the retry loop at the app layer (instead of delegating to MeshCore)
    /// to provide per-attempt UI feedback. On each attempt, it:
    /// - Updates the message status in the database
    /// - Notifies the UI via `retryStatusHandler`
    /// - Switches to flood routing after `floodAfter` failed attempts
    /// - Notifies UI of routing changes via `routingChangedHandler`
    ///
    /// - Parameters:
    ///   - messageID: The message ID for status updates
    ///   - contactID: The contact ID for routing change notifications
    ///   - radioID: The device ID for saving contact updates
    ///   - publicKey: The full 32-byte destination public key
    ///   - text: The message text
    ///   - timestamp: The message timestamp (must remain constant across retries)
    ///   - timestampRaw: The same timestamp as a `UInt32` epoch-seconds value. The
    ///     caller must pass the same integer it used to build `timestamp: Date` —
    ///     resampling inside the loop would break expected-ACK precomputation,
    ///     since the firmware hash is keyed off the exact `UInt32` that ends up
    ///     on the wire.
    ///   - timeout: Optional custom timeout per attempt (nil = use device suggested)
    ///
    /// - Returns: `MessageSentInfo` if ACK received, `nil` if all attempts exhausted
    /// - Throws: `MeshCoreError` if send fails with unrecoverable error
    private func sendDirectMessageWithRetryLoop(
        messageID: UUID,
        contactID: UUID,
        radioID: UUID,
        publicKey: Data,
        text: String,
        timestamp: Date,
        timestampRaw: UInt32,
        timeout: TimeInterval?
    ) async throws -> MessageSentInfo? {
        var attempts = 0
        var floodAttempts = 0
        var isFloodMode = false

        while attempts < config.maxAttempts && (!isFloodMode || floodAttempts < config.maxFloodAttempts) {
            // Check for task cancellation
            guard !Task.isCancelled else {
                throw CancellationError()
            }

            // Update database and notify UI of retry status (only after first attempt fails)
            if attempts > 0 {
                try await dataStore.updateMessageRetryStatus(
                    id: messageID,
                    status: .retrying,
                    retryAttempt: attempts - 1,
                    maxRetryAttempts: config.maxAttempts - 1
                )
                await retryStatusHandler?(messageID, attempts - 1, config.maxAttempts - 1)
            }

            // Switch to flood routing after floodAfter direct attempts
            if attempts == config.floodAfter && !isFloodMode {
                logger.info("Resetting path to flood after \(attempts) failed attempts")
                do {
                    try await session.resetPath(publicKey: publicKey)
                    isFloodMode = true

                    // Notify UI of routing change and save updated contact
                    if let updatedContact = try await session.getContact(publicKey: publicKey) {
                        _ = try await dataStore.saveContact(radioID: radioID, from: updatedContact.toContactFrame())
                    }
                    await routingChangedHandler?(contactID, true)
                } catch {
                    logger.warning("Failed to reset path: \(error.localizedDescription), continuing...")
                    // Continue anyway - device might handle it
                    isFloodMode = true
                }
            }

            if attempts > 0 {
                logger.info("Retry sending message: attempt \(attempts + 1)/\(config.maxAttempts)")
            }

            // Precompute the expected ACK CRC before the send so the persistent
            // ACK listener cannot race ahead of trackPendingAck on short direct links.
            guard let senderPublicKey = await session.currentSelfInfo?.publicKey else {
                throw MessageServiceError.notConnected
            }

            let predictedAck = AckCodeBuilder.expectedAck(
                timestamp: timestampRaw,
                attempt: UInt8(attempts),
                text: text,
                senderPublicKey: senderPublicKey
            )

            // Pre-send floor must outlive one checkExpiredAcks tick; otherwise a
            // BLE round-trip > 1s could let the checker expire the speculative
            // entry before sendMessage returns and we overwrite the timeout with
            // the authoritative sentInfo-derived value.
            let preSendTimeout = max(timeout ?? config.minTimeout, checkInterval)
            trackPendingAck(
                messageID: messageID,
                contactID: contactID,
                ackCode: predictedAck,
                timeout: preSendTimeout
            )

            let sentInfo: MessageSentInfo
            do {
                sentInfo = try await withPoolBackoff(transientCode: FirmwareDeviceErrorCode.directMessageTableFull, config: config.poolBackoff, logger: logger) {
                    try await session.sendMessage(
                        to: publicKey.prefix(6),
                        text: text,
                        timestamp: timestamp,
                        attempt: UInt8(attempts)
                    )
                }
            } catch {
                // Drop the whole speculative entry on send failure. A partial
                // remove would leave an empty-codes PendingAck visible to
                // checkExpiredAcks if failMessageAndRethrow is ever bypassed.
                pendingAcks.removeValue(forKey: messageID)
                throw error
            }

            // If the persistent ACK listener consumed our predicted ACK during
            // sendMessage's cross-actor suspension, the entry is already
            // removed or marked delivered. Short-circuit before waitForEvent
            // so the retry loop doesn't clobber .delivered via
            // updateMessageRetryStatus and broadcast a duplicate DM.
            guard let tracked = pendingAcks[messageID], !tracked.isDelivered else {
                return sentInfo
            }

            let ackTimeout = timeout ?? max(
                config.minTimeout,
                Double(sentInfo.suggestedTimeoutMs) / 1000.0 * 1.2
            )

            if sentInfo.expectedAck != predictedAck {
                logger.warning(
                    "expectedAck mismatch for \(messageID) attempt \(attempts): predicted \(predictedAck.hexString()) vs firmware \(sentInfo.expectedAck.hexString()); merging firmware code"
                )
                trackPendingAck(
                    messageID: messageID,
                    contactID: contactID,
                    ackCode: sentInfo.expectedAck,
                    timeout: ackTimeout
                )
            } else {
                // Re-stamp sentAt so checkExpiredAcks measures timeout from
                // send-return, not from the speculative insert.
                pendingAcks[messageID]?.timeout = ackTimeout
                pendingAcks[messageID]?.sentAt = Date()
            }

            let ackEvent = await session.waitForEvent(
                filter: .acknowledgement(code: sentInfo.expectedAck),
                timeout: ackTimeout
            )

            if ackEvent != nil {
                logger.info("Message acknowledged on attempt \(attempts + 1)")
                return sentInfo
            }

            // Listener may have consumed the ACK between the pre-send guard
            // and waitForEvent's subscription becoming active; re-check before
            // retrying so we don't resend a DM the firmware already delivered.
            if pendingAcks[messageID]?.isDelivered != false {
                return sentInfo
            }

            // ACK timeout - increment counters and retry
            attempts += 1
            if isFloodMode {
                floodAttempts += 1
            }
        }

        logger.warning("Message delivery failed after \(attempts) attempts")
        return nil
    }

    // MARK: - Routing Change Detection

    /// Checks if contact routing changed and notifies handler if so.
    ///
    /// Called after sendMessageWithRetry to detect if routing switched
    /// between direct and flood modes during the retry process.
    private func checkAndNotifyRoutingChange(
        publicKey: Data,
        contactID: UUID,
        radioID: UUID,
        initialPathLength: UInt8
    ) async {
        do {
            // Fetch fresh contact state from device
            guard let updatedContact = try await session.getContact(publicKey: publicKey) else {
                logger.info("Contact not found in device contacts after retry")
                return
            }

            // Check if routing changed
            let newPathLength = updatedContact.outPathLength
            if newPathLength != initialPathLength {
                logger.info("Routing changed for contact \(contactID): \(initialPathLength) -> \(newPathLength)")

                // Save updated contact to database
                _ = try await dataStore.saveContact(radioID: radioID, from: updatedContact.toContactFrame())

                // Notify UI of routing change
                let isNowFlood = newPathLength == 0xFF
                await routingChangedHandler?(contactID, isNowFlood)
            }
        } catch {
            logger.warning("Failed to check routing change: \(error.localizedDescription)")
        }
    }

    // MARK: - Private Helpers

    /// Records a new expected-ACK code for a message awaiting delivery.
    ///
    /// On the first call for a given `messageID` this creates the entry; on
    /// subsequent retry attempts it adds the new `ackCode` to the same entry's
    /// `ackCodes` set and resets both `sentAt` and the timeout window to the
    /// latest attempt so `checkExpiredAcks` does not fail the in-flight retry.
    func trackPendingAck(messageID: UUID, contactID: UUID, ackCode: Data, timeout: TimeInterval) {
        // Diagnostic: the firmware ACK CRC is derived from (timestamp, attempt,
        // text, sender key) with no recipient, so identical text sent in the
        // same second to different contacts produces the same code. Counting
        // collisions across distinct in-flight messages measures how often a
        // delivery confirmation could be attributed to the wrong message.
        if let colliding = pendingAcks.first(where: { $0.key != messageID && $0.value.ackCodes.contains(ackCode) }) {
            logger.warning("[ack-diag] ackCode collision: code=\(ackCode.hexString()) shared by messages \(colliding.key) and \(messageID)")
        }
        if var existing = pendingAcks[messageID] {
            existing.ackCodes.insert(ackCode)
            existing.sentAt = Date()
            existing.timeout = timeout
            pendingAcks[messageID] = existing
        } else {
            pendingAcks[messageID] = PendingAck(
                messageID: messageID,
                contactID: contactID,
                ackCodes: [ackCode],
                sentAt: Date(),
                timeout: timeout
            )
        }
    }

    private func validateDirectMessage(text: String, to contact: ContactDTO) throws {
        guard contact.type != .repeater else { throw MessageServiceError.invalidRecipient }
        guard text.utf8.count <= ProtocolLimits.maxDirectMessageLength else { throw MessageServiceError.messageTooLong }
    }

    func finalizeSend(
        messageID: UUID,
        contactID: UUID,
        radioID: UUID,
        publicKey: Data,
        sentInfo: MessageSentInfo?,
        initialPathLength: UInt8
    ) async throws -> MessageDTO {
        // Atomically take ownership of the pendingAcks entry. A missing entry
        // means `handleAcknowledgement` already processed the ACK (it removes
        // the entry on delivery); an `isDelivered == true` entry means the
        // listener marked it mid-flight. In both cases the listener owns the
        // `.delivered` write (including `roundTripTime`) and we skip the DB
        // update here to avoid clobbering it with a nil RTT.
        let tracking = pendingAcks.removeValue(forKey: messageID)

        if tracking?.isDelivered == false {
            if let sentInfo {
                try await dataStore.updateMessageAck(
                    id: messageID,
                    ackCode: sentInfo.expectedAck.ackCodeUInt32,
                    status: .delivered
                )
                try await dataStore.updateContactLastMessage(contactID: contactID, date: Date())
                await ackConfirmationHandler?(messageID, .delivered, nil)
            } else {
                let didFail = try await dataStore.updateMessageStatusUnlessDelivered(id: messageID, status: .failed)
                if didFail {
                    await messageFailedHandler?(messageID)
                }
            }
        }
        await checkAndNotifyRoutingChange(
            publicKey: publicKey,
            contactID: contactID,
            radioID: radioID,
            initialPathLength: initialPathLength
        )
        guard let message = try await dataStore.fetchMessage(id: messageID) else {
            throw MessageServiceError.sendFailed("Failed to fetch message")
        }
        return message
    }

    private func createOutgoingMessage(
        id: UUID,
        radioID: UUID,
        contactID: UUID,
        text: String,
        timestamp: UInt32,
        textType: TextType,
        replyToID: UUID?
    ) -> MessageDTO {
        let message = Message(
            id: id,
            radioID: radioID,
            contactID: contactID,
            text: text,
            timestamp: timestamp,
            directionRawValue: MessageDirection.outgoing.rawValue,
            statusRawValue: MessageStatus.pending.rawValue,
            textTypeRawValue: textType.rawValue,
            replyToID: replyToID
        )
        return MessageDTO(from: message)
    }
}
