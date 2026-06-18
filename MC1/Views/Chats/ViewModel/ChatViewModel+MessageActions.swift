import SwiftUI
import MC1Services

extension ChatViewModel {

    // MARK: - Message Actions

    /// Retry sending a failed direct message through the persistent send
    /// queue. Replaces the prior multi-await sequence (delete +
    /// status flip + direct service call) with a single
    /// `PersistenceStore.replacePendingSendForRetry` transaction followed
    /// by a no-persist `signalDMEnqueued`. The queue drain owns
    /// transport-open parking and process-restart durability, so a
    /// disconnect or app death mid-retry now replays from the persisted
    /// `PendingSend` row on the next hydrate.
    func retryMessage(_ message: MessageDTO) async {
        logger.info("retryMessage called for message: \(message.id)")

        guard !retryInFlight else { return }
        retryInFlight = true
        defer { retryInFlight = false }

        guard let contact = currentContact,
              let dataStore else {
            logger.warning("retryMessage: missing currentContact or dataStore")
            return
        }

        errorMessage = nil
        errorBannerMessage = nil

        let envelope = DirectMessageEnvelope(messageID: message.id, contactID: contact.id)
        let dto = PendingSendDTO(envelope: envelope, radioID: contact.radioID)

        do {
            _ = try await dataStore.replacePendingSendForRetry(messageID: message.id, dto: dto)
        } catch {
            logger.error("retryMessage: replacePendingSendForRetry failed for messageID=\(message.id, privacy: .public): \(String(describing: error))")
            sendErrorMessage = Self.copyForEnqueueFailure(error)
            return
        }

        coordinator?.applyStatusUpdate(
            messageID: message.id,
            status: .pending,
            userInitiated: true
        )

        do {
            try await signalDMEnqueued(envelope)
        } catch {
            logger.error("retryMessage: signalDMEnqueued failed for messageID=\(message.id, privacy: .public): \(String(describing: error))")
            sendErrorMessage = Self.copyForEnqueueFailure(error)
        }
    }

    /// Resend a channel message in place, or copy text for direct messages.
    /// Used for "Send Again" context menu action.
    func sendAgain(_ message: MessageDTO) async {
        if let channelIndex = message.channelIndex {
            // Channel messages: enqueue with isResend: true so the queue drain
            // refreshes the mesh timestamp via resendChannelMessage. Reusing the
            // original timestamp would hash identically to the failed packet and
            // be dropped by the 128-slot cyclic dedup table at every neighbor.
            let envelope = ChannelMessageEnvelope(
                messageID: message.id,
                channelIndex: channelIndex,
                isResend: true,
                messageText: message.text,
                messageTimestamp: message.timestamp,
                localNodeName: connectedDeviceProvider()?.nodeName
            )
            do {
                try await enqueueChannel(envelope)
            } catch {
                logger.error("enqueueChannel sendAgain failed for messageID=\(message.id, privacy: .public): \(String(describing: error))")
                _ = try? await dataStore?.updateMessageStatusUnlessDelivered(id: message.id, status: .failed)
                coordinator?.applyStatusUpdate(messageID: message.id, status: .failed)
                sendErrorMessage = Self.copyForEnqueueFailure(error)
            }
        } else {
            // Identity-preserving DM resend. Mirrors retryMessage: route through
            // the shared PersistenceStore.replacePendingSendForRetry transaction
            // so delete-existing-PendingSend + status flip + new-PendingSend
            // insert land atomically, then signal the queue. retryMessage and
            // sendAgain intentionally remain two separate functions: retryMessage
            // debounces via retryInFlight, sendAgain via actions-sheet dismissal —
            // extracting a shared body would lose that distinction.
            guard let contact = currentContact,
                  let dataStore else {
                logger.warning("sendAgain (DM): missing currentContact or dataStore")
                return
            }

            let envelope = DirectMessageEnvelope(messageID: message.id, contactID: contact.id, isResend: true)
            let dto = PendingSendDTO(envelope: envelope, radioID: contact.radioID)

            do {
                _ = try await dataStore.replacePendingSendForRetry(messageID: message.id, dto: dto)
            } catch {
                logger.error("sendAgain (DM): replacePendingSendForRetry failed for messageID=\(message.id, privacy: .public): \(String(describing: error))")
                sendErrorMessage = Self.copyForEnqueueFailure(error)
                return
            }

            coordinator?.applyStatusUpdate(
                messageID: message.id,
                status: .pending,
                userInitiated: true
            )

            do {
                try await signalDMEnqueued(envelope)
            } catch {
                logger.error("sendAgain (DM): signalDMEnqueued failed for messageID=\(message.id, privacy: .public): \(String(describing: error))")
                sendErrorMessage = Self.copyForEnqueueFailure(error)
            }
        }
    }

    /// Delete a single message
    func deleteMessage(_ message: MessageDTO) async {
        guard connectionStateProvider() == .ready else { return }
        guard let dataStore else { return }

        do {
            try await dataStore.deleteMessage(id: message.id)

            // Remove from all local collections
            coordinator?.remove(messageID: message.id)
            coordinator?.removeRenderItem(id: message.id)

            // Clean up preview state for deleted message
            cleanupPreviewState(for: message.id)

            // Re-derive the conversation's last-message date from the database
            // rather than the paginated in-memory window: older messages beyond
            // the loaded page must keep the conversation visible. Always refresh
            // the chat list afterward — deleting the newest message shifts the
            // conversation's sort position and preview even when older messages
            // remain, and deleting the last one removes it entirely.
            if let currentContact {
                try await dataStore.recomputeContactLastMessageDate(contactID: currentContact.id)
                syncCoordinator?.notifyConversationsChanged()
            }
        } catch {
            errorMessage = error.userFacingMessage
        }
    }

    /// Clears a direct conversation by dropping its messages and last-message date (a local
    /// SwiftData write, no radio command). Throws `ConversationActionError.notConnected` rather
    /// than returning silently so the caller can roll back the optimistic hide and surface an error.
    func deleteDirectConversation(for contact: ContactDTO) async throws {
        guard connectionStateProvider() == .ready, let dataStore else {
            throw ConversationActionError.notConnected
        }

        try await dataStore.deleteMessagesForContact(contactID: contact.id)
        try await dataStore.clearUnreadCount(contactID: contact.id)
        try await dataStore.updateContactLastMessage(contactID: contact.id, date: nil)
        await notificationService?.updateBadgeCount()
    }
}
