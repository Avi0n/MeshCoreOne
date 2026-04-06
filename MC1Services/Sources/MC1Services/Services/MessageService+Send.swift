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
    ///   - text: The message text to send (max 200 characters)
    ///   - contact: The recipient contact
    ///   - textType: The text encoding type (defaults to `.plain`)
    ///   - replyToID: Optional ID of message being replied to
    ///
    /// - Returns: The created message DTO with pending/sent status
    ///
    /// - Throws:
    ///   - `MessageServiceError.invalidRecipient` if contact is a repeater
    ///   - `MessageServiceError.messageTooLong` if text exceeds 200 characters
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

        // Save message to store as pending first
        let messageDTO = createOutgoingMessage(
            id: messageID,
            deviceID: contact.deviceID,
            contactID: contact.id,
            text: text,
            timestamp: timestamp,
            textType: textType,
            replyToID: replyToID
        )
        try await dataStore.saveMessage(messageDTO)

        // Single send attempt
        do {
            let sentInfo = try await session.sendMessage(
                to: contact.publicKey,
                text: text,
                timestamp: Date(timeIntervalSince1970: TimeInterval(timestamp))
            )

            let ackCodeUInt32 = sentInfo.expectedAck.ackCodeUInt32

            // Update message with ACK code
            try await dataStore.updateMessageAck(
                id: messageID,
                ackCode: ackCodeUInt32,
                status: .sent
            )

            // Track pending ACK
            let timeout = TimeInterval(sentInfo.suggestedTimeoutMs) / 1000.0 * 1.2
            trackPendingAck(messageID: messageID, ackCode: sentInfo.expectedAck, timeout: timeout)

            // Update contact's last message date
            try await dataStore.updateContactLastMessage(contactID: contact.id, date: Date())

            guard let message = try await dataStore.fetchMessage(id: messageID) else {
                throw MessageServiceError.sendFailed("Failed to fetch saved message")
            }
            return message
        } catch {
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
    ///   - text: The message text to send (max 200 characters)
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
    ///   - `MessageServiceError.messageTooLong` if text exceeds 200 characters
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
            deviceID: contact.deviceID,
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
                deviceID: contact.deviceID,
                publicKey: contact.publicKey,
                text: text,
                timestamp: Date(timeIntervalSince1970: TimeInterval(timestamp)),
                timeout: timeout > 0 ? timeout : nil
            )

            return try await finalizeSend(
                messageID: messageID,
                contactID: contact.id,
                deviceID: contact.deviceID,
                publicKey: contact.publicKey,
                sentInfo: sentInfo,
                initialPathLength: initialPathLength
            )
        } catch {
            try await failMessageAndRethrow(error, messageID: messageID)
        }
    }

    /// Creates a pending message without sending it.
    ///
    /// Use this when you want to show the message in the UI immediately
    /// and retry it later via ``retryDirectMessage(messageID:to:)``.
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
            deviceID: contact.deviceID,
            contactID: contact.id,
            text: text,
            timestamp: timestamp,
            textType: textType,
            replyToID: replyToID
        )
        try await dataStore.saveMessage(messageDTO)

        return messageDTO
    }

    /// Retries sending a failed message with automatic retry logic.
    ///
    /// Use this method to retry messages that previously failed. The retry uses the same
    /// automatic retry logic as `sendMessageWithRetry`, including flood routing fallback.
    ///
    /// - Parameters:
    ///   - messageID: The ID of the failed message to retry
    ///   - contact: The recipient contact
    ///
    /// - Returns: The updated message DTO with new delivery status
    ///
    /// - Throws:
    ///   - `MessageServiceError.sendFailed` if message not found or retry already in progress
    ///   - `MessageServiceError.sessionError` if MeshCore send fails
    ///
    /// # Example
    ///
    /// ```swift
    /// let message = try await messageService.retryDirectMessage(
    ///     messageID: failedMessage.id,
    ///     to: contact
    /// )
    /// ```
    public func retryDirectMessage(
        messageID: UUID,
        to contact: ContactDTO
    ) async throws -> MessageDTO {
        // Guard against concurrent retries
        guard !inFlightRetries.contains(messageID) else {
            logger.warning("Retry already in progress for message: \(messageID)")
            throw MessageServiceError.sendFailed("Retry already in progress")
        }

        inFlightRetries.insert(messageID)
        defer { inFlightRetries.remove(messageID) }

        // Capture initial routing state to detect changes
        let initialPathLength = contact.outPathLength

        guard let existingMessage = try await dataStore.fetchMessage(id: messageID) else {
            throw MessageServiceError.sendFailed("Message not found")
        }

        // Run app-layer retry loop with UI notifications
        do {
            let sentInfo = try await sendDirectMessageWithRetryLoop(
                messageID: messageID,
                contactID: contact.id,
                deviceID: contact.deviceID,
                publicKey: contact.publicKey,
                text: existingMessage.text,
                timestamp: Date(timeIntervalSince1970: TimeInterval(existingMessage.timestamp)),
                timeout: nil
            )

            return try await finalizeSend(
                messageID: messageID,
                contactID: contact.id,
                deviceID: contact.deviceID,
                publicKey: contact.publicKey,
                sentInfo: sentInfo,
                initialPathLength: initialPathLength
            )
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
    ///   - deviceID: The device ID for saving contact updates
    ///   - publicKey: The full 32-byte destination public key
    ///   - text: The message text
    ///   - timestamp: The message timestamp (must remain constant across retries)
    ///   - timeout: Optional custom timeout per attempt (nil = use device suggested)
    ///
    /// - Returns: `MessageSentInfo` if ACK received, `nil` if all attempts exhausted
    /// - Throws: `MeshCoreError` if send fails with unrecoverable error
    private func sendDirectMessageWithRetryLoop(
        messageID: UUID,
        contactID: UUID,
        deviceID: UUID,
        publicKey: Data,
        text: String,
        timestamp: Date,
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
                        _ = try await dataStore.saveContact(deviceID: deviceID, from: updatedContact.toContactFrame())
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

            // Send the message
            let sentInfo = try await session.sendMessage(
                to: publicKey.prefix(6),
                text: text,
                timestamp: timestamp,
                attempt: UInt8(attempts)
            )

            // Wait for ACK with timeout
            let ackTimeout = timeout ?? max(
                config.minTimeout,
                Double(sentInfo.suggestedTimeoutMs) / 1000.0 * 1.2
            )

            let ackEvent = await session.waitForEvent(
                matching: { event in
                    if case .acknowledgement(let code, _) = event {
                        return code == sentInfo.expectedAck
                    }
                    return false
                },
                timeout: ackTimeout
            )

            if ackEvent != nil {
                logger.info("Message acknowledged on attempt \(attempts + 1)")
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
        deviceID: UUID,
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
                _ = try await dataStore.saveContact(deviceID: deviceID, from: updatedContact.toContactFrame())

                // Notify UI of routing change
                let isNowFlood = newPathLength == 0xFF
                await routingChangedHandler?(contactID, isNowFlood)
            }
        } catch {
            logger.warning("Failed to check routing change: \(error.localizedDescription)")
        }
    }

    // MARK: - Send Channel Message

    /// Sends a broadcast message to a channel.
    ///
    /// Channel messages are broadcast to all devices listening on the specified channel.
    /// No acknowledgement is expected or tracked for channel messages.
    ///
    /// - Parameters:
    ///   - text: The message text to broadcast (max 200 characters)
    ///   - channelIndex: The channel index (0-7)
    ///   - deviceID: The local device ID
    ///   - textType: The text encoding type (defaults to `.plain`)
    ///
    /// - Returns: The ID of the created message
    ///
    /// - Throws:
    ///   - `MessageServiceError.messageTooLong` if text exceeds 200 characters
    ///   - `MessageServiceError.channelNotFound` if channel index is invalid
    ///   - `MessageServiceError.sessionError` if MeshCore send fails
    ///
    /// # Example
    ///
    /// ```swift
    /// let messageID = try await messageService.sendChannelMessage(
    ///     text: "Hello channel!",
    ///     channelIndex: 0,
    ///     deviceID: device.id
    /// )
    /// ```
    public func sendChannelMessage(
        text: String,
        channelIndex: UInt8,
        deviceID: UUID,
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
            deviceID: deviceID,
            channelIndex: channelIndex,
            text: text,
            timestamp: timestamp,
            textType: textType
        )
        try await dataStore.saveMessage(messageDTO)

        do {
            try await session.sendChannelMessage(
                channel: channelIndex,
                text: text,
                timestamp: Date(timeIntervalSince1970: TimeInterval(timestamp))
            )
        } catch {
            try await failMessageAndRethrow(error, messageID: messageID)
        }

        // Broadcast succeeded — update status and channel metadata.
        // These throw to the caller if they fail, but don't mark the message as
        // .failed since the broadcast already went out.
        try await dataStore.updateMessageStatus(id: messageID, status: .sent)

        if let channel = try await dataStore.fetchChannel(deviceID: deviceID, index: channelIndex) {
            try await dataStore.updateChannelLastMessage(channelID: channel.id, date: Date())
        }

        return (id: messageID, timestamp: timestamp)
    }

    /// Creates a pending channel message without sending it.
    ///
    /// Use this for optimistic UI — the message is saved immediately and can be
    /// displayed in the conversation while the actual send happens in the background
    /// via ``sendPendingChannelMessage(messageID:channelIndex:deviceID:)``.
    ///
    /// - Parameters:
    ///   - text: The message text
    ///   - channelIndex: The channel index to send on
    ///   - deviceID: The device ID
    ///   - textType: The text type (defaults to `.plain`)
    ///
    /// - Returns: The created message DTO with pending status
    public func createPendingChannelMessage(
        text: String,
        channelIndex: UInt8,
        deviceID: UUID,
        textType: TextType = .plain
    ) async throws -> MessageDTO {
        guard text.utf8.count <= ProtocolLimits.maxChannelMessageTotalLength else {
            throw MessageServiceError.messageTooLong
        }

        let messageID = UUID()
        let timestamp = UInt32(Date().timeIntervalSince1970)

        let messageDTO = createOutgoingChannelMessage(
            id: messageID,
            deviceID: deviceID,
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
    /// Use this after ``createPendingChannelMessage(text:channelIndex:deviceID:textType:)``
    /// to transmit the message over the mesh. Updates the message status to `.sent` on
    /// success or `.failed` on error.
    ///
    /// - Parameter messageID: The ID of the pending message to send
    public func sendPendingChannelMessage(messageID: UUID) async throws {
        guard let message = try await dataStore.fetchMessage(id: messageID) else {
            throw MessageServiceError.sendFailed("Message not found")
        }
        guard let channelIndex = message.channelIndex else {
            throw MessageServiceError.sendFailed("Not a channel message")
        }

        do {
            try await session.sendChannelMessage(
                channel: channelIndex,
                text: message.text,
                timestamp: Date(timeIntervalSince1970: TimeInterval(message.timestamp))
            )
        } catch {
            try await failMessageAndRethrow(error, messageID: messageID)
        }

        try await dataStore.updateMessageStatus(id: messageID, status: .sent)

        if let channel = try await dataStore.fetchChannel(deviceID: message.deviceID, index: channelIndex) {
            try await dataStore.updateChannelLastMessage(channelID: channel.id, date: Date())
        }
    }

    /// Resend an existing channel message, incrementing its send count.
    ///
    /// This is used for "Send Again" - it re-transmits the same message
    /// rather than creating a duplicate. Uses a new timestamp so the mesh
    /// treats it as a fresh broadcast.
    public func resendChannelMessage(messageID: UUID) async throws {
        guard let message = try await dataStore.fetchMessage(id: messageID) else {
            throw MessageServiceError.sendFailed("Message not found")
        }
        guard let channelIndex = message.channelIndex else {
            throw MessageServiceError.sendFailed("Not a channel message")
        }

        let now = Date()
        let newTimestamp = UInt32(now.timeIntervalSince1970)

        // Re-send via mesh with new timestamp (fresh broadcast)
        try await session.sendChannelMessage(
            channel: channelIndex,
            text: message.text,
            timestamp: now
        )

        // Update stored timestamp so the mesh treats this as a new broadcast
        try await dataStore.updateMessageTimestamp(id: messageID, timestamp: newTimestamp)

        // Increment send count
        _ = try await dataStore.incrementMessageSendCount(id: messageID)

        // Reset heard repeats so the count reflects only the new send
        try await dataStore.updateMessageHeardRepeats(id: messageID, heardRepeats: 0)
        try await dataStore.deleteMessageRepeats(messageID: messageID)
    }

    // MARK: - Private Helpers

    private func trackPendingAck(messageID: UUID, ackCode: Data, timeout: TimeInterval) {
        let pending = PendingAck(
            messageID: messageID,
            ackCode: ackCode,
            sentAt: Date(),
            timeout: timeout
        )
        pendingAcks[ackCode] = pending
    }

    private func validateDirectMessage(text: String, to contact: ContactDTO) throws {
        guard contact.type != .repeater else { throw MessageServiceError.invalidRecipient }
        guard text.utf8.count <= ProtocolLimits.maxDirectMessageLength else { throw MessageServiceError.messageTooLong }
    }

    private func failMessageAndRethrow(_ error: Error, messageID: UUID) async throws -> Never {
        try await dataStore.updateMessageStatus(id: messageID, status: .failed)
        if let meshError = error as? MeshCoreError {
            throw MessageServiceError.sessionError(meshError)
        }
        throw error
    }

    private func finalizeSend(
        messageID: UUID,
        contactID: UUID,
        deviceID: UUID,
        publicKey: Data,
        sentInfo: MessageSentInfo?,
        initialPathLength: UInt8
    ) async throws -> MessageDTO {
        if let sentInfo {
            try await dataStore.updateMessageAck(
                id: messageID,
                ackCode: sentInfo.expectedAck.ackCodeUInt32,
                status: .delivered
            )
            try await dataStore.updateContactLastMessage(contactID: contactID, date: Date())
        } else {
            try await dataStore.updateMessageStatus(id: messageID, status: .failed)
        }
        await checkAndNotifyRoutingChange(
            publicKey: publicKey,
            contactID: contactID,
            deviceID: deviceID,
            initialPathLength: initialPathLength
        )
        guard let message = try await dataStore.fetchMessage(id: messageID) else {
            throw MessageServiceError.sendFailed("Failed to fetch message")
        }
        return message
    }

    private func createOutgoingMessage(
        id: UUID,
        deviceID: UUID,
        contactID: UUID,
        text: String,
        timestamp: UInt32,
        textType: TextType,
        replyToID: UUID?
    ) -> MessageDTO {
        let message = Message(
            id: id,
            deviceID: deviceID,
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

    private func createOutgoingChannelMessage(
        id: UUID,
        deviceID: UUID,
        channelIndex: UInt8,
        text: String,
        timestamp: UInt32,
        textType: TextType
    ) -> MessageDTO {
        let message = Message(
            id: id,
            deviceID: deviceID,
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
