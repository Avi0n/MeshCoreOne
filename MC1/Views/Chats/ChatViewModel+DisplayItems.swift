import Foundation
import MC1Services

extension ChatViewModel {

    // MARK: - Display Items

    /// Optimistically append a message if not already present. Called
    /// synchronously before async reload to ensure `ChatTableView` sees
    /// the new count immediately for unread tracking.
    func appendMessageIfNew(_ message: MessageDTO) {
        guard let coordinator else { return }
        let previous = messages.last

        // Synchronous append: coordinator append, render item insertion, and
        // channel sender bookkeeping all mutate Observable state on the main
        // actor in one call frame, so SwiftUI already invalidates dependent
        // views once per change cycle without an explicit transaction.
        guard coordinator.append(message) else { return }
        let newItem = makeItem(for: message, previous: previous)
        coordinator.appendRenderItem(newItem)
        if let senderName = message.senderNodeName,
           let radioID = currentChannel?.radioID {
            addChannelSenderIfNew(senderName, radioID: radioID, timestamp: message.timestamp)
        }

        // URL detection writes `cachedURLs[messageID]` from a background task
        // and lands as its own invalidation cycle.
        let messageID = message.id
        let text = message.text
        let generation = urlDetectionGeneration
        Task { [weak self] in
            guard let self else { return }
            await self.updateURLForDisplayItem(messageID: messageID, text: text, generation: generation)
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

        guard let coordinator,
              let message = coordinator.messagesByID[messageID] else {
            logger.warning("URL update for missing message id \(messageID)")
            return
        }
        let previous = previousMessage(for: messageID)
        coordinator.updateRenderItem(id: messageID) { _ in
            makeItem(for: message, previous: previous)
        }
    }

    /// Build MessageItems with pre-computed properties. Snapshots view-model
    /// state on the main actor and delegates the per-message builder loop to
    /// `ChatCoordinator.rebuildItems`, which performs the off-actor hop and
    /// applies on main only when the captured `renderStateID` still matches.
    func buildItems() {
        guard let coordinator else { return }
        urlDetectionGeneration &+= 1
        let urlGeneration = urlDetectionGeneration

        var uncachedMessageIDs: [(UUID, String)] = []
        let messagesSnapshot = coordinator.messages

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

        coordinator.rebuildItems(inputs: inputs, envInputs: envInputs) { [weak self] in
            guard let self else { return }
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

    /// Get full message DTO for a MessageItem.
    /// Logs a warning if lookup fails (indicates data inconsistency).
    func message(for item: MessageItem) -> MessageDTO? {
        guard let message = messagesByID[item.id] else {
            logger.warning("Message lookup failed for item id=\(item.id)")
            return nil
        }
        return message
    }
}
