import Foundation
import MC1Services

extension ChatViewModel {

    // MARK: - DM Send Queue

    func makeDMSendQueue() -> SendQueue<DirectMessageEnvelope> {
        let sendContext = self.sendContext
        let logger = self.logger
        return SendQueue<DirectMessageEnvelope>(
            send: { envelope in
                let services = await MainActor.run {
                    (dataStore: sendContext.dataStore, messageService: sendContext.messageService)
                }
                // Mark the row failed before throwing so the UI bubble
                // surfaces the failure rather than spinning in "sending"
                // forever. The queue only ever holds .pending / .sending
                // envelopes, so plain updateMessageStatus is correct here.
                guard let messageService = services.messageService else {
                    if let dataStore = services.dataStore {
                        try? await dataStore.updateMessageStatus(
                            id: envelope.messageID,
                            status: .failed
                        )
                    }
                    throw ChatSendQueueError.servicesUnavailable
                }
                guard let dataStore = services.dataStore else {
                    throw ChatSendQueueError.servicesUnavailable
                }
                guard let contact = try? await dataStore.fetchContact(id: envelope.contactID) else {
                    logger.info("Skipping queued message - contact \(envelope.contactID) was deleted")
                    return
                }
                _ = try await messageService.retryDirectMessage(
                    messageID: envelope.messageID,
                    to: contact
                )
            },
            onError: { _, _ in
                // Error reporting deferred to onDrain so the post-drain
                // loadMessages reset of errorMessage = nil does not clobber
                // sendErrorMessage either.
            },
            onDrain: { [weak self] lastError in
                await self?.handleDMQueueDrain(lastError: lastError)
            }
        )
    }

    /// Called after the DM send queue empties. Reloads the current
    /// conversation and conversations list so optimistic UI catches up
    /// with server state, then surfaces the most recent send-side error
    /// (if any) into `sendErrorMessage` after the reloads — `loadMessages`
    /// and `loadConversations` both clear `errorMessage = nil` at their
    /// top, but neither touches `sendErrorMessage`.
    private func handleDMQueueDrain(lastError: Error?) async {
        let contact = currentContact
        if let contact {
            await loadMessages(for: contact)
        }
        if let radioID = contact?.radioID {
            await loadConversations(radioID: radioID)
        }
        if let lastError {
            // The localized template body is what the user sees. The
            // specific error stays in the log for developers — most
            // underlying errors here are MeshCoreError, which is
            // English-only via its retroactive LocalizedError conformance.
            sendErrorMessage = L10n.Chats.Chats.Alert.UnableToSend.message
            logger.error("DM send queue drain ended with error: \(String(describing: lastError))")
        }
    }

    // MARK: - Channel Send Queue

    func makeChannelSendQueue() -> SendQueue<ChannelMessageEnvelope> {
        let sendContext = self.sendContext
        return SendQueue<ChannelMessageEnvelope>(
            send: { envelope in
                let services = await MainActor.run {
                    (
                        dataStore: sendContext.dataStore,
                        messageService: sendContext.messageService,
                        reactionService: sendContext.reactionService
                    )
                }
                guard let messageService = services.messageService else {
                    if let dataStore = services.dataStore {
                        try? await dataStore.updateMessageStatus(
                            id: envelope.messageID,
                            status: .failed
                        )
                    }
                    throw ChatSendQueueError.servicesUnavailable
                }

                if envelope.isResend {
                    // Resend stamps a fresh timestamp so the retry packet hashes
                    // differently from the original. The mesh dedup table is a
                    // 128-slot cyclic ring with no time-based eviction; reusing
                    // the original timestamp would be silently dropped at every
                    // neighbor until the slot rotates out.
                    try await messageService.resendChannelMessage(messageID: envelope.messageID)
                } else {
                    try await messageService.sendPendingChannelMessage(messageID: envelope.messageID)
                }

                // Reaction indexing is best-effort post-send. Missing
                // reactionService or localNodeName (test config, anonymous
                // send) is a soft state — leave silent rather than throwing.
                // localNodeName comes from the envelope so a rename between
                // enqueue and drain still tags the message with the name
                // the user had at the moment they hit Send.
                if let reactionService = services.reactionService,
                   let nodeName = envelope.localNodeName {
                    _ = await reactionService.indexMessage(
                        id: envelope.messageID,
                        channelIndex: envelope.channelIndex,
                        senderName: nodeName,
                        text: envelope.messageText,
                        timestamp: envelope.messageTimestamp
                    )
                }
            },
            onError: { _, _ in
                // Error reporting deferred to onDrain so the post-drain
                // loadChannelMessages reset of errorMessage = nil does not
                // clobber sendErrorMessage either.
            },
            onDrain: { [weak self] lastError in
                await self?.handleChannelQueueDrain(lastError: lastError)
            }
        )
    }

    /// Called after the channel send queue empties. Reloads the current
    /// channel and channels list so optimistic UI catches up with server
    /// state, then surfaces the most recent send-side error (if any) into
    /// sendErrorMessage after the reloads — loadChannelMessages and
    /// loadChannels both clear errorMessage = nil at their top, but
    /// neither touches sendErrorMessage.
    private func handleChannelQueueDrain(lastError: Error?) async {
        let channel = currentChannel
        if let channel {
            await loadChannelMessages(for: channel)
            await loadChannels(radioID: channel.radioID)
        }
        if let lastError {
            sendErrorMessage = L10n.Chats.Chats.Alert.UnableToSend.message
            logger.error("Channel send queue drain ended with error: \(String(describing: lastError))")
        }
    }
}
