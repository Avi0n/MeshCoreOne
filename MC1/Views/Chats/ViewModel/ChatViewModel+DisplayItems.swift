import Foundation
import MC1Services

extension ChatViewModel {
  // MARK: - Display Items

  /// Optimistically append a message if not already present. Called from
  /// the incoming admission path after the receive-time prefetch resolves
  /// or hits its timeout, and from the outgoing send paths immediately
  /// after `createPendingMessage`. Preserves unread-counter math via the
  /// `newItems.count` delta in `ChatTableView.updateItems`.
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
      await updateURLForDisplayItem(messageID: messageID, text: text, generation: generation)
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

    // Gate every per-VM write on the message still being present so a
    // delete between detection-start and detection-end can never seed
    // orphan state that no cleanup path purges before conversation
    // switch.
    guard let coordinator,
          let message = coordinator.messagesByID[messageID] else {
      logger.warning("URL update for missing message id \(messageID)")
      return
    }

    cachedURLs[messageID] = detectedURL

    // Rehydrate decoded-image state from the singleton when this is a
    // fresh chat-entry on a URL whose decode already completed in a
    // prior session. Painting `.loaded` plus the dict entry in the
    // same call frame means the next render skips the shimmer
    // transition entirely.
    rehydrateInlineImageStateIfCached(messageID: messageID, url: detectedURL)
    rehydratePreviewStateIfCached(messageID: messageID, url: detectedURL)

    let previous = previousMessage(for: messageID)
    coordinator.updateRenderItem(id: messageID) { _ in
      makeItem(for: message, previous: previous)
    }
  }

  /// Seed `decodedImages` / `imageIsGIF` / `previewStates = .loaded`
  /// atomically when the singleton has a decoded image for this URL.
  /// Also restores raw bytes into `loadedImageData` for static images so
  /// the full-screen viewer and share sheet (which need original
  /// resolution and `Data`) keep working post-rehydration. Idempotent
  /// and a no-op for non-image URLs, the master toggle being off, or a
  /// per-VM state that has already advanced past a tap-to-load-eligible
  /// state. Master-gated only, no scope check: this reads the decoded cache
  /// and performs no network fetch, so a cached image beats the tap-to-load
  /// placeholder under scope-off too, matching `LinkPreviewCache.preview`'s
  /// cache-before-gate ordering for cards.
  private func rehydrateInlineImageStateIfCached(messageID: UUID, url: URL?) {
    guard envInputs.previewsEnabled,
          let url,
          ImageURLClassifier.isImageURL(url) else { return }
    let existingState = previewStates[messageID]
    guard existingState == nil || existingState == .idle || existingState == .disabled else { return }
    let directURL = ImageURLClassifier.directImageURL(for: url)
    guard let cached = InlineImageCache.shared.decoded(for: directURL) else { return }
    applyDecodedImage(cached, for: messageID)
  }

  /// Seed `loadedPreviews` / `decodedPreviewAssets` / `previewStates = .loaded`
  /// atomically when `DecodedPreviewCache` already holds a decoded card for
  /// this URL. Painting `.loaded` in the same call frame as URL detection
  /// means the bubble skips the loading shimmer on chat re-entry. Idempotent
  /// and a no-op once state has advanced past `.idle`; image URLs are handled
  /// by `rehydrateInlineImageStateIfCached` and have no preview entry here.
  private func rehydratePreviewStateIfCached(messageID: UUID, url: URL?) {
    guard let url else { return }
    let existingState = previewStates[messageID]
    guard existingState == nil || existingState == .idle else { return }
    guard let cached = DecodedPreviewCache.shared.decoded(for: url) else { return }
    loadedPreviews[messageID] = cached.dto
    decodedPreviewAssets[messageID] = DecodedPreviewAssets(image: cached.hero, icon: cached.icon)
    previewStates[messageID] = .loaded
  }

  /// Build MessageItems with pre-computed properties. Snapshots view-model
  /// state on the main actor and delegates the per-message builder loop to
  /// `ChatCoordinator.rebuildItems`, which performs the off-actor hop and
  /// applies on main only when the captured `renderStateID` still matches.
  func buildItems() {
    guard let coordinator else { return }
    urlDetectionGeneration &+= 1
    let urlGeneration = urlDetectionGeneration

    // Drop stale entries from the previous build before `makeBuildInputs`
    // re-inserts. Theme toggle and offline-state flip both rebuild items
    // under a new request key for the same message; without this, the old
    // key's bucket lingers and a late resolution could rebuild a row whose
    // current request key has changed.
    mapPreviewRequestIndex.removeAll()

    var uncachedMessageIDs: [(UUID, String)] = []
    let messagesSnapshot = coordinator.messages

    let inputs: [(MessageDTO, MessageBuildInputs)] = messagesSnapshot.enumerated().map { index, message in
      let previous: MessageDTO? = index > 0 ? messagesSnapshot[index - 1] : nil

      if cachedURLs[message.id] == nil,
         previewStates[message.id] == nil,
         loadedPreviews[message.id] == nil {
        uncachedMessageIDs.append((message.id, message.text))
      }

      return (message, makeBuildInputs(for: message, previous: previous))
    }

    let messagesToDetect = uncachedMessageIDs

    coordinator.rebuildItems(inputs: inputs, envInputs: envInputs) { [weak self] in
      guard let self else { return }
      if !messagesToDetect.isEmpty {
        urlDetectionTask?.cancel()
        urlDetectionTask = Task { [weak self] in
          guard let self else { return }
          for (messageID, text) in messagesToDetect {
            guard !Task.isCancelled, urlDetectionGeneration == urlGeneration else { return }
            await updateURLForDisplayItem(messageID: messageID, text: text, generation: urlGeneration)
          }
        }
      }
      decodeLegacyPreviewImages()
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
