import Foundation
import MC1Services

extension ChatViewModel {

    // MARK: - Persist + Enqueue Wrappers

    /// Persist + enqueue a DM envelope. The persist step writes a
    /// `PendingSend` row keyed by `radioID`; the enqueue step appends to
    /// the in-memory `SendQueue`. On send success or non-cancellation
    /// failure the wrapper inside `makeDMSendQueue()` removes the row.
    func enqueueDM(_ envelope: DirectMessageEnvelope) async {
        await persistDM(envelope)
        await dmSendQueue?.enqueue(envelope)
    }

    /// Persist + enqueue a channel envelope. See `enqueueDM` for shape notes.
    func enqueueChannel(_ envelope: ChannelMessageEnvelope) async {
        await persistChannel(envelope)
        await channelSendQueue?.enqueue(envelope)
    }

    private func persistDM(_ envelope: DirectMessageEnvelope) async {
        guard let dataStore = sendContext.dataStore,
              let radioID = currentRadioID else { return }
        do {
            let dto = PendingSendDTO(envelope: envelope, radioID: radioID)
            _ = try await dataStore.insertPendingSendAssigningSequence(dto)
        } catch {
            logger.error("Persisting DM envelope failed: \(String(describing: error))")
        }
    }

    private func persistChannel(_ envelope: ChannelMessageEnvelope) async {
        guard let dataStore = sendContext.dataStore,
              let radioID = currentRadioID else { return }
        do {
            let dto = PendingSendDTO(envelope: envelope, radioID: radioID)
            _ = try await dataStore.insertPendingSendAssigningSequence(dto)
        } catch {
            logger.error("Persisting channel envelope failed: \(String(describing: error))")
        }
    }

    // MARK: - Teardown

    /// Cancel any in-flight drain on the DM and channel queues plus the
    /// hydration and build tasks. Intended for test teardown so a
    /// suspending send closure does not keep the SendQueue actor alive
    /// past the view-model's intended lifetime.
    func cancelPendingDrain() async {
        await dmSendQueue?.cancelDrain()
        await channelSendQueue?.cancelDrain()
        hydrationTask?.cancel()
        buildItemsTask?.cancel()
    }

    // MARK: - Hydration

    /// Replay persisted pending sends back into the in-memory queues for
    /// the given radio. Called once per radioID per view-model lifetime
    /// so reconnect or process restart drains messages enqueued during a
    /// previous session — without ever replaying the same row twice.
    ///
    /// Idempotence: `hydratedRadios` guards against double hydration when
    /// `configure(...)` fires twice for the same radio. The check is cheap;
    /// the fetch is the expensive part. If hydration does not run to
    /// completion (cancellation mid-loop, fetch failure, missing data
    /// store) the radioID is removed from `hydratedRadios` so a follow-up
    /// configure on the same radio re-attempts.
    ///
    /// Enqueues bypass the persist step (rows are already in DB) by
    /// invoking the SendQueue actor directly rather than going through
    /// enqueueDM / enqueueChannel.
    func hydrateSendQueues(radioID: UUID) {
        guard hydratedRadios.insert(radioID).inserted else { return }
        guard let dataStore = sendContext.dataStore else {
            hydratedRadios.remove(radioID)
            return
        }
        hydrationTask?.cancel()
        hydrationTask = Task { [weak self] in
            guard let self else { return }
            do {
                let rows = try await dataStore.fetchPendingSends(radioID: radioID)
                for dto in rows {
                    if Task.isCancelled {
                        await MainActor.run { self.hydratedRadios.remove(radioID) }
                        return
                    }
                    switch dto.kind {
                    case .dm:
                        if let envelope = dto.directMessageEnvelope() {
                            await self.dmSendQueue?.enqueue(envelope)
                        }
                    case .channel:
                        if let envelope = dto.channelMessageEnvelope() {
                            await self.channelSendQueue?.enqueue(envelope)
                        }
                    }
                }
            } catch is CancellationError {
                await MainActor.run { self.hydratedRadios.remove(radioID) }
            } catch {
                await MainActor.run {
                    self.logger.error("Hydrating send queues failed: \(String(describing: error))")
                    self.hydratedRadios.remove(radioID)
                }
            }
        }
    }

    // MARK: - DM Send Queue

    func makeDMSendQueue() -> SendQueue<DirectMessageEnvelope> {
        let sendContext = self.sendContext
        let logger = self.logger
        return SendQueue<DirectMessageEnvelope>(
            send: { envelope in
                let services = await MainActor.run {
                    (dataStore: sendContext.dataStore, messageService: sendContext.messageService)
                }
                guard let messageService = services.messageService else {
                    // A queued message awaiting a radio reconnect is `.pending`,
                    // not `.failed`. Throw a transient error so `onError`
                    // preserves the row for a future drain.
                    throw ChatSendQueueError.transientUnavailable
                }
                guard let dataStore = services.dataStore else {
                    throw ChatSendQueueError.servicesUnavailable
                }
                guard let contact = try? await dataStore.fetchContact(id: envelope.contactID) else {
                    logger.info("Skipping queued message - contact \(envelope.contactID) was deleted")
                    try? await dataStore.deletePendingSendsForMessage(messageID: envelope.messageID)
                    return
                }
                _ = try await messageService.retryDirectMessage(
                    messageID: envelope.messageID,
                    to: contact
                )
                try? await dataStore.deletePendingSendsForMessage(messageID: envelope.messageID)
            },
            onError: { error, envelope in
                let dataStore = await MainActor.run { sendContext.dataStore }
                if let chatError = error as? ChatSendQueueError, chatError.isTransient {
                    return
                }
                try? await dataStore?.deletePendingSendsForMessage(messageID: envelope.messageID)
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
                    // A queued message awaiting a radio reconnect is `.pending`,
                    // not `.failed`. Throw a transient error so `onError`
                    // preserves the row for a future drain.
                    throw ChatSendQueueError.transientUnavailable
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

                try? await services.dataStore?.deletePendingSendsForMessage(messageID: envelope.messageID)
            },
            onError: { error, envelope in
                let dataStore = await MainActor.run { sendContext.dataStore }
                if let chatError = error as? ChatSendQueueError, chatError.isTransient {
                    return
                }
                try? await dataStore?.deletePendingSendsForMessage(messageID: envelope.messageID)
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
