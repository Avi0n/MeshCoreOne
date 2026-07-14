import Foundation
import MC1Services

extension ChatViewModel {
  // MARK: - Display Items

  /// Optimistically append a message if not already present. Called from
  /// the incoming admission path after the receive-time prefetch resolves
  /// or hits its timeout, and from the outgoing send paths immediately
  /// after `createPendingMessage`. Preserves unread-counter math via the
  /// item-count delta observed by `ChatTiledView`.
  func appendMessageIfNew(_ message: MessageDTO) {
    guard coordinator != nil, let timelineWriter else { return }
    let previous = messages.last

    // Synchronous append: coordinator append, render item insertion, and
    // channel sender bookkeeping all mutate Observable state on the main
    // actor in one call frame, so SwiftUI already invalidates dependent
    // views once per change cycle without an explicit transaction.
    guard timelineWriter.append(message) else { return }
    let newItem = makeItem(for: message, previous: previous)
    timelineWriter.appendRenderItem(newItem)
    if let senderName = message.senderNodeName,
       let radioID = currentChannel?.radioID {
      addChannelSenderIfNew(senderName, radioID: radioID, timestamp: message.timestamp)
    }

    // URL detection and cache rehydration happen synchronously inside
    // `makeItem` (see `seedPreviewStateIfNeeded`), so the appended row is
    // already carrying its preview fragment.
  }

  /// Synchronously detects the message's first URL and rehydrates preview /
  /// inline-image state from the process-lifetime decoded caches, before the
  /// build inputs are snapshotted. A fresh view model (every open, prewarm,
  /// or refresh) starts with cold dictionaries while the shared coordinator's
  /// items are already on screen; without this seed the first rebuild bakes
  /// the link-preview fragment as `.idle` (a zero-height `EmptyView`) and the
  /// list visibly reflows when async detection later restores the card.
  /// Idempotent: the `cachedURLs` sentinel (present-but-nil marks "detected,
  /// no URL") makes repeat calls a dictionary hit.
  func seedPreviewStateIfNeeded(for message: MessageDTO) {
    guard cachedURLs[message.id] == nil else { return }
    let url = LinkPreviewService.extractFirstURL(from: message.text)
    cachedURLs[message.id] = url
    rehydrateInlineImageStateIfCached(messageID: message.id, url: url)
    rehydratePreviewStateIfCached(messageID: message.id, url: url)
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
    guard let coordinator, let timelineWriter else { return }

    // Drop stale entries from the previous build before `makeBuildInputs`
    // re-inserts. Theme toggle and offline-state flip both rebuild items
    // under a new request key for the same message; without this, the old
    // key's bucket lingers and a late resolution could rebuild a row whose
    // current request key has changed.
    mapPreviewRequestIndex.removeAll()

    let messagesSnapshot = coordinator.messages

    // Drop formatted-text entries for messages no longer in the timeline
    // (conversation switch, deletion). Guarded so it is a no-op during normal
    // pagination, where the cache only ever grows toward the message count.
    if formattedTextCache.count > messagesSnapshot.count {
      let liveIDs = Set(messagesSnapshot.map(\.id))
      formattedTextCache = formattedTextCache.filter { liveIDs.contains($0.key) }
    }

    // URL detection and decoded-cache rehydration run synchronously inside
    // `makeBuildInputs` (see `seedPreviewStateIfNeeded`), so every row leaves
    // this loop already carrying its preview fragment at a stable height.
    let inputs: [(MessageDTO, MessageBuildInputs)] = messagesSnapshot.enumerated().map { index, message in
      let previous: MessageDTO? = index > 0 ? messagesSnapshot[index - 1] : nil
      return (message, makeBuildInputs(for: message, previous: previous))
    }

    timelineWriter.rebuildItems(inputs: inputs, envInputs: envInputs) { [weak self] in
      self?.decodeLegacyPreviewImages()
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
