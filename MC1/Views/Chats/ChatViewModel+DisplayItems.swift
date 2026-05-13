import Foundation
import MC1Services

extension ChatViewModel {

    // MARK: - Display Items

    /// Optimistically append a message if not already present.
    /// Called synchronously before async reload to ensure ChatTableView
    /// sees the new count immediately for unread tracking.
    func appendMessageIfNew(_ message: MessageDTO) {
        guard renderState.itemIndexByID[message.id] == nil else { return }
        let previous = messages.last
        messages.append(message)
        bumpBuildGeneration()
        messagesByID[message.id] = message

        let newItem = makeItem(for: message, previous: previous)
        renderState = renderState.appendingItem(newItem)

        let messageID = message.id
        let text = message.text
        let generation = urlDetectionGeneration
        Task { [weak self] in
            guard let self else { return }
            await self.updateURLForDisplayItem(messageID: messageID, text: text, generation: generation)
        }

        if let senderName = message.senderNodeName,
           let radioID = currentChannel?.radioID {
            addChannelSenderIfNew(senderName, radioID: radioID)
        }
    }

    /// Update URL detection for a single message by ID.
    /// Uses O(1) dictionary lookup to handle concurrent array modifications.
    private func updateURLForDisplayItem(messageID: UUID, text: String, generation: UInt64) async {
        let detectedURL = await Task.detached(priority: .userInitiated) {
            LinkPreviewService.extractFirstURL(from: text)
        }.value

        // Drop stale writes after a buildItems rebuild — Task.cancel only kills the latest chain link.
        // Single-row rebuilds via rebuildDisplayItem(for:) do not write cachedURLs and need no
        // generation gating; only this URL-detection writer does.
        guard urlDetectionGeneration == generation else { return }

        cachedURLs[messageID] = detectedURL

        guard let message = messagesByID[messageID] else {
            logger.warning("URL update for missing message id \(messageID)")
            return
        }
        let previous = previousMessage(for: messageID)
        renderState = renderState.updatingItem(id: messageID) { _ in
            makeItem(for: message, previous: previous)
        }
        bumpBuildGeneration()
    }

    /// Build MessageItems with pre-computed properties.
    /// Snapshots view-model state on the main actor, runs the per-message
    /// builder loop off-actor inside a `Task { @concurrent in }` hop, and
    /// applies the result back on main only when the captured `buildGeneration`
    /// still matches — guarding against races where a fresher mutation lands
    /// while an older build is still in flight.
    func buildItems() {
        bumpBuildGeneration()
        let myGeneration = currentBuildGeneration()
        urlDetectionGeneration &+= 1
        let urlGeneration = urlDetectionGeneration

        messagesByID = Dictionary(uniqueKeysWithValues: messages.map { ($0.id, $0) })

        var uncachedMessageIDs: [(UUID, String)] = []
        let messagesSnapshot = messages
        let envInputsSnapshot = envInputs

        let inputs: [(MessageDTO, MessageBuildInputs)] = messagesSnapshot.enumerated().map { index, message in
            let previous: MessageDTO? = index > 0 ? messagesSnapshot[index - 1] : nil

            if cachedURLs[message.id] == nil
                && previewStates[message.id] == nil
                && loadedPreviews[message.id] == nil {
                uncachedMessageIDs.append((message.id, message.text))
            }

            return (message, makeBuildInputs(for: message, previous: previous))
        }

        let messagesToDetect = uncachedMessageIDs

        buildItemsTask?.cancel()
        buildItemsTask = Task(priority: .userInitiated) { @concurrent [weak self] in
            var builtItems: [MessageItem] = []
            builtItems.reserveCapacity(inputs.count)
            for (message, perMessageInputs) in inputs {
                if Task.isCancelled { return }
                builtItems.append(
                    MessageFragmentBuilder.makeItem(
                        for: message,
                        inputs: perMessageInputs,
                        envInputs: envInputsSnapshot
                    )
                )
            }

            await MainActor.run {
                guard let self else { return }
                guard self.currentBuildGeneration() == myGeneration else {
                    // A fresher mutation landed while this build was off-main.
                    // Schedule another build so renderState eventually reflects
                    // the current state — without this, single-row updates that
                    // landed in the gap (and no-op'd because items weren't yet
                    // applied) would leave renderState stale until the next
                    // unrelated trigger.
                    self.buildItems()
                    return
                }
                let indexByID = Dictionary(uniqueKeysWithValues:
                    builtItems.enumerated().map { ($0.element.id, $0.offset) })
                self.renderState = self.renderState.with(items: builtItems, itemIndexByID: indexByID)

                if !messagesToDetect.isEmpty {
                    self.urlDetectionTask?.cancel()
                    self.urlDetectionTask = Task { [weak self] in
                        guard let self else { return }
                        for (messageID, text) in messagesToDetect {
                            guard !Task.isCancelled, self.urlDetectionGeneration == urlGeneration else { return }
                            await self.updateURLForDisplayItem(messageID: messageID, text: text, generation: urlGeneration)
                        }
                    }
                }

                self.decodeLegacyPreviewImages()
            }
        }
    }
}
