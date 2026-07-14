import MC1Services
import SwiftUI
import UIKit

extension ChatViewModel {
  // MARK: - Receive-Time Prefetch

  /// Default maximum time the receive pipeline waits for a prefetch to
  /// resolve before admitting the bubble at its text-only size. After
  /// this, the bubble may still morph in later when the background
  /// fetch lands; see `handleDimensionResolution(_:)`.
  static let defaultPrefetchTimeout: Duration = .seconds(3)

  /// Admit an incoming message to the display-items array after racing
  /// its URL prefetches against `prefetchTimeout` (default: 3s).
  /// Messages without URLs admit immediately. Outgoing messages bypass
  /// this and use the instant-render path in the send methods.
  func admitIncomingMessage(_ message: MessageDTO, isChannelMessage: Bool) async {
    guard let prefetcher else {
      appendMessageIfNew(message)
      return
    }
    // Master off: no fragment is built, so the prefetcher's card-branch cache
    // lookups would be pure waste per received message. Skipping also drops the
    // 3s admission race for these messages. `LinkPreviewCache.preview`
    // self-gates regardless, so this is an efficiency guard, not the privacy gate.
    guard envInputs.previewsEnabled,
          !LinkPreviewService.extractAllURLs(in: message.text).isEmpty else {
      appendMessageIfNew(message)
      return
    }

    let text = message.text
    let timeout = prefetchTimeout
    let allowImageProbes = linkPreviewPreferences.shouldAutoResolve(isChannelMessage: isChannelMessage)
    await withTaskGroup(of: Void.self) { group in
      group.addTask {
        await prefetcher.prefetch(
          urlsIn: text,
          isChannelMessage: isChannelMessage,
          allowImageProbes: allowImageProbes
        )
      }
      group.addTask {
        try? await Task.sleep(for: timeout)
      }
      for await _ in group {
        group.cancelAll()
        break
      }
    }

    appendMessageIfNew(message)
  }

  /// Fan-out hook for the inline image dimensions resolution stream.
  /// Triggered when a probe lands after a message has already been admitted —
  /// either because the receive-time prefetch hit its timeout, the user
  /// retried, or the message arrived during background sync. Every message
  /// whose body contains the resolved URL is rebuilt so the bubble picks up
  /// the now-known `cachedAspect`.
  func handleDimensionResolution(_ url: URL) async {
    guard let coordinator else { return }
    let target = url.absoluteString
    let affected = coordinator.messages
      .filter { $0.text.contains(target) }
      .map(\.id)
    for messageID in affected {
      rebuildDisplayItem(for: messageID)
    }
  }

  /// A snapshot finished: rebuild only the rows that show it, found via the
  /// request-keyed index (O(matches)), never by scanning every loaded message.
  func handleSnapshotResolution(_ request: MapSnapshotRequest) {
    guard let messageIDs = mapPreviewRequestIndex[request] else { return }
    for messageID in messageIDs {
      rebuildDisplayItem(for: messageID)
    }
  }

  /// Outgoing-message exception to the withhold-and-release rule: the
  /// bubble was added immediately at text-only height for instant send
  /// feedback, so the prefetch runs in parallel and the cell height
  /// morphs in via `rebuildDisplayItem` once the prefetch lands. The
  /// `.easeOut(0.25)` cross-fade lives at the view layer.
  func schedulePrefetchForOutgoingMessage(_ message: MessageDTO, isChannelMessage: Bool) {
    guard let prefetcher else { return }
    // Master off: nothing to prefetch (see `admitIncomingMessage`).
    guard envInputs.previewsEnabled,
          !LinkPreviewService.extractAllURLs(in: message.text).isEmpty else { return }
    let messageID = message.id
    let text = message.text
    let allowImageProbes = linkPreviewPreferences.shouldAutoResolve(isChannelMessage: isChannelMessage)
    Task { [weak self] in
      await prefetcher.prefetch(
        urlsIn: text,
        isChannelMessage: isChannelMessage,
        allowImageProbes: allowImageProbes
      )
      self?.rebuildDisplayItem(for: messageID)
    }
  }

  // MARK: - Preview State Management

  /// Request preview fetch for a message (called when cell becomes visible)
  func requestPreviewFetch(for messageID: UUID) {
    guard previewStates[messageID] == nil || previewStates[messageID] == .idle else { return }
    guard let url = cachedURLs[messageID].flatMap(\.self) else { return }

    let isChannel = currentChannel != nil

    previewFetchTasks[messageID] = Task {
      await fetchPreview(for: messageID, url: url, isChannelMessage: isChannel)
    }
  }

  /// Fetch preview for a message and update state
  private func fetchPreview(for messageID: UUID, url: URL, isChannelMessage: Bool) async {
    guard let dataStore, let linkPreviewCache else { return }

    // Check malware domain blocklist before fetching
    if let host = url.host(), await MalwareDomainFilter.shared.isBlocked(host) {
      previewStates[messageID] = .malwareWarning
      rebuildDisplayItem(for: messageID)
      return
    }

    // Update to loading state
    previewStates[messageID] = .loading
    rebuildDisplayItem(for: messageID)

    // Get preview from cache (handles all tiers: memory, database, network)
    let result = await linkPreviewCache.preview(
      for: url,
      using: dataStore,
      isChannelMessage: isChannelMessage
    )

    // Check if task was cancelled (message scrolled away or conversation changed)
    guard !Task.isCancelled else {
      previewFetchTasks.removeValue(forKey: messageID)
      return
    }

    // Update state based on result
    switch result {
    case let .loaded(dto):
      persistHeroAspect(from: dto, requestedURL: url)
      await decodeAndStorePreviewImages(from: dto, for: messageID)
      previewStates[messageID] = .loaded
      loadedPreviews[messageID] = dto

    case .loading:
      // Still loading (duplicate request), keep current state
      break

    case .noPreviewAvailable, .failed:
      previewStates[messageID] = .noPreview

    case .disabled:
      previewStates[messageID] = .disabled
    }

    previewFetchTasks.removeValue(forKey: messageID)
    rebuildDisplayItem(for: messageID)
  }

  /// Manually fetch preview (for tap-to-load when previews disabled)
  func manualFetchPreview(for messageID: UUID) async {
    guard let url = cachedURLs[messageID].flatMap(\.self),
          let dataStore,
          let linkPreviewCache else { return }

    previewStates[messageID] = .loading
    rebuildDisplayItem(for: messageID)

    let result = await linkPreviewCache.manualFetch(for: url, using: dataStore)

    switch result {
    case let .loaded(dto):
      persistHeroAspect(from: dto, requestedURL: url)
      await decodeAndStorePreviewImages(from: dto, for: messageID)
      previewStates[messageID] = .loaded
      loadedPreviews[messageID] = dto
    case .loading:
      break
    case .noPreviewAvailable, .failed, .disabled:
      previewStates[messageID] = .noPreview
    }

    rebuildDisplayItem(for: messageID)
  }

  /// Persist the preview hero's aspect ratio keyed by the message's page URL
  /// (the key `makeBuildInputs` looks up), so the next build's loading shimmer
  /// reserves the final card footprint instead of guessing `fallbackAspect`.
  private func persistHeroAspect(from dto: LinkPreviewDataDTO, requestedURL: URL) {
    guard let width = dto.imageWidth, let height = dto.imageHeight,
          let store = inlineImageDimensionsStore else { return }
    Task {
      await store.save(url: requestedURL, size: CGSize(width: width, height: height))
    }
  }

  /// Warm link-preview metadata (and inline-image dimensions) for the newest
  /// page rows, so a later open builds cards synchronously from the caches at
  /// their final height. Called by the background prime paths (navigation
  /// prefetch, arrival-time refresh); a no-op without a configured prefetcher
  /// or with previews off. `LinkPreviewCache` dedups in-flight and cached
  /// URLs, so repeat calls cost one dictionary hit per URL.
  func prewarmRecentPreviews(limit: Int = 10) async {
    guard let prefetcher, envInputs.previewsEnabled else { return }
    let isChannel = currentChannel != nil
    let allowImageProbes = linkPreviewPreferences.shouldAutoResolve(isChannelMessage: isChannel)
    for message in messages.suffix(limit)
      where !LinkPreviewService.extractAllURLs(in: message.text).isEmpty {
      await prefetcher.prefetch(
        urlsIn: message.text,
        isChannelMessage: isChannel,
        allowImageProbes: allowImageProbes
      )
    }
  }

  /// Decode preview hero image and icon off the main thread and store results
  private func decodeAndStorePreviewImages(from dto: LinkPreviewDataDTO, for messageID: UUID) async {
    async let heroResult: UIImage? = {
      guard let data = dto.imageData else { return nil }
      return await Task.detached { ImageURLDetector.downsampledImage(from: data) }.value
    }()
    async let iconResult: UIImage? = {
      guard let data = dto.iconData else { return nil }
      return await Task.detached { ImageURLDetector.downsampledImage(from: data) }.value
    }()
    let (hero, icon) = await (heroResult, iconResult)

    // Warm the process-lifetime cache before the per-VM apply so a
    // chat-exit mid-decode still surfaces the card on the next visit, and a
    // later re-entry repaints loaded without re-decoding. Cache every
    // resolved preview, including title-only cards with no hero or icon, so
    // those skip the loading shimmer on re-entry too.
    if let url = URL(string: dto.url) {
      DecodedPreviewCache.shared.store(
        CachedDecodedPreview(dto: dto, hero: hero, icon: icon),
        for: url
      )
    }

    guard hero != nil || icon != nil else { return }
    decodedPreviewAssets[messageID] = DecodedPreviewAssets(image: hero, icon: icon)
  }

  /// Update a message in place and rebuild its display item.
  func updateMessage(id: UUID, mutation: (inout MessageDTO) -> Void) {
    guard let coordinator,
          coordinator.messagesByID[id] != nil else { return }
    coordinator.update(messageID: id, mutation)
    rebuildDisplayItem(for: id)
  }

  /// Rebuild a single MessageItem with current preview, image, and message
  /// state. No-ops when the message is no longer present.
  func rebuildDisplayItem(for messageID: UUID) {
    guard let coordinator,
          let message = coordinator.messagesByID[messageID] else {
      logger.warning("rebuild requested for missing message id \(messageID)")
      return
    }
    let previous = previousMessage(for: messageID)
    coordinator.updateRenderItem(id: messageID) { _ in
      makeItem(for: message, previous: previous)
    }
  }

  /// Cancel preview fetch for a message (called when cell scrolls away)
  func cancelPreviewFetch(for messageID: UUID) {
    previewFetchTasks[messageID]?.cancel()
    previewFetchTasks.removeValue(forKey: messageID)
  }

  /// Clear all preview state (called on conversation switch)
  func clearPreviewState() {
    previewFetchTasks.values.forEach { $0.cancel() }
    previewFetchTasks.removeAll()
    previewStates.removeAll()
    loadedPreviews.removeAll()
    decodedPreviewAssets.removeAll()
    legacyPreviewDecodeInFlight.removeAll()
    cachedURLs.removeAll()
    imageURLsServingPages.removeAll()
    mapPreviewRequestIndex.removeAll()
    clearImageState()
  }

  /// Clean up preview state for a specific message (called on message deletion)
  func cleanupPreviewState(for messageID: UUID) {
    previewStates.removeValue(forKey: messageID)
    loadedPreviews.removeValue(forKey: messageID)
    decodedPreviewAssets.removeValue(forKey: messageID)
    previewFetchTasks[messageID]?.cancel()
    previewFetchTasks.removeValue(forKey: messageID)
    removeFromMapPreviewIndex(messageID)
    cleanupImageState(for: messageID)
  }

  /// Drops a deleted message from every map-preview request bucket so a late
  /// snapshot resolution does not try to rebuild a row that no longer exists.
  private func removeFromMapPreviewIndex(_ messageID: UUID) {
    for request in Array(mapPreviewRequestIndex.keys) {
      guard var ids = mapPreviewRequestIndex[request], ids.remove(messageID) != nil else { continue }
      if ids.isEmpty {
        mapPreviewRequestIndex.removeValue(forKey: request)
      } else {
        mapPreviewRequestIndex[request] = ids
      }
    }
  }

  // MARK: - Inline Image State Management

  /// Atomically seeds the per-VM image state from a decoded cache entry.
  /// Restores `loadedImageData` from the entry's raw bytes when available
  /// so the full-screen viewer and share sheet keep working after
  /// rehydration. Does not rebuild the render item; the caller owns that
  /// step.
  func applyDecodedImage(_ cached: CachedDecodedImage, for messageID: UUID) {
    decodedImages[messageID] = cached.image
    imageIsGIF[messageID] = cached.isGIF
    if let data = cached.data {
      loadedImageData.setObject(data as NSData, forKey: messageID as NSUUID, cost: data.count)
    }
    previewStates[messageID] = .loaded
  }

  /// Returns the pre-decoded UIImage for a message, if available
  func decodedImage(for messageID: UUID) -> UIImage? {
    decodedImages[messageID]
  }

  /// Returns the pre-decoded link preview hero image for a message
  func decodedPreviewImage(for messageID: UUID) -> UIImage? {
    decodedPreviewAssets[messageID]?.image
  }

  /// Returns the pre-decoded link preview icon for a message
  func decodedPreviewIcon(for messageID: UUID) -> UIImage? {
    decodedPreviewAssets[messageID]?.icon
  }

  /// Pre-decode images for legacy messages with embedded preview data
  func decodeLegacyPreviewImages() {
    for message in messages where message.linkPreviewURL != nil {
      let id = message.id
      let existing = decodedPreviewAssets[id]
      let needsImageDecode = message.linkPreviewImageData != nil && existing?.image == nil
      let needsIconDecode = message.linkPreviewIconData != nil && existing?.icon == nil
      guard needsImageDecode || needsIconDecode,
            !legacyPreviewDecodeInFlight.contains(id) else { continue }

      let imageData = message.linkPreviewImageData
      let iconData = message.linkPreviewIconData

      legacyPreviewDecodeInFlight.insert(id)
      Task { [weak self] in
        async let heroResult: UIImage? = if needsImageDecode, let imageData {
          await Task.detached { ImageURLDetector.downsampledImage(from: imageData) }.value
        } else {
          existing?.image
        }
        async let iconResult: UIImage? = if needsIconDecode, let iconData {
          await Task.detached { ImageURLDetector.downsampledImage(from: iconData) }.value
        } else {
          existing?.icon
        }
        let (hero, icon) = await (heroResult, iconResult)
        if hero != nil || icon != nil {
          self?.decodedPreviewAssets[id] = DecodedPreviewAssets(image: hero, icon: icon)
          self?.rebuildDisplayItem(for: id)
        }
        self?.legacyPreviewDecodeInFlight.remove(id)
      }
    }
  }

  /// Returns whether the image for a message is a GIF
  func isGIFImage(for messageID: UUID) -> Bool {
    imageIsGIF[messageID] ?? false
  }

  /// Returns the raw image data for a message, if available
  func imageData(for messageID: UUID) -> Data? {
    loadedImageData.object(forKey: messageID as NSUUID).map { Data(referencing: $0) }
  }

  /// Clears the negative cache entry for a failed image and re-triggers the
  /// fetch. A visible retry is an explicit user action, so it bypasses the
  /// scope gate and fetches directly, same rationale as `manualFetchPreview`;
  /// routing back through `requestImageFetch` would bounce a scope-off failure
  /// to the tap-to-load placeholder instead of retrying.
  func retryImageFetch(for messageID: UUID) async {
    guard envInputs.previewsEnabled,
          previewStates[messageID] != .malwareWarning else { return }
    guard let url = cachedURLs[messageID].flatMap(\.self),
          ImageURLClassifier.isImageURL(url) else { return }

    let directURL = ImageURLClassifier.directImageURL(for: url)
    await InlineImageCache.shared.clearFailure(for: directURL)

    imageFetchTasks[messageID] = Task {
      await fetchInlineImage(for: messageID, url: url)
    }
  }

  /// Whether `url` routes to the inline-image fragment and fetch path: an
  /// image-extension or resolvable URL the fetch path has not since found to
  /// serve an HTML page. `imageURLsServingPages` covers reroutes discovered
  /// this session; `InlineImageCache.servesHTMLPage` carries the verdict across
  /// chat re-entry so a reloaded card is not re-classified as a stranded
  /// inline-image shimmer.
  func routesToInlineImage(_ url: URL) -> Bool {
    guard ImageURLClassifier.isImageURL(url),
          !imageURLsServingPages.contains(url.absoluteString) else { return false }
    return !InlineImageCache.shared.servesHTMLPage(ImageURLClassifier.directImageURL(for: url))
  }

  /// Whether the `onRequestPreviewFetch` callback should route to image
  /// fetching instead of link-preview fetching for the given message.
  /// Encapsulates the cached-URL + image-URL + master-toggle gate so the cell
  /// callback stays a single line. Scope is deliberately not checked here:
  /// image URLs must still route to `requestImageFetch`, which owns the
  /// `.disabled` transition; routing them to the preview path would fetch a
  /// card for an image URL.
  func shouldRequestImageFetch(for messageID: UUID) -> Bool {
    guard envInputs.previewsEnabled,
          let url = cachedURLs[messageID].flatMap(\.self) else {
      return false
    }
    return routesToInlineImage(url)
  }

  /// Request inline image fetch for a message (called when cell becomes visible)
  func requestImageFetch(for messageID: UUID) {
    guard envInputs.previewsEnabled else { return }
    guard previewStates[messageID] == nil || previewStates[messageID] == .idle else { return }
    guard let url = cachedURLs[messageID].flatMap(\.self),
          ImageURLClassifier.isImageURL(url) else { return }

    // Master on but auto-resolve off for this conversation type: park the
    // state at `.disabled` so the cell renders the tap-to-load placeholder and
    // its `.task(id:)` re-fire loop terminates, mirroring the card path's
    // `LinkPreviewCache.preview` returning `.disabled`. No network fetch.
    guard linkPreviewPreferences.shouldAutoResolve(isChannelMessage: currentChannel != nil) else {
      previewStates[messageID] = .disabled
      rebuildDisplayItem(for: messageID)
      return
    }

    imageFetchTasks[messageID] = Task {
      await fetchInlineImage(for: messageID, url: url)
    }
  }

  /// Manually fetch an inline image (tap-to-load when auto-resolve is off for
  /// this conversation type). Bypasses the scope gate, like `manualFetchPreview`
  /// does for cards; `fetchInlineImage` sets `.loading`, runs the malware check,
  /// and handles the `.notImage` reroute.
  func manualFetchImage(for messageID: UUID) {
    guard envInputs.previewsEnabled,
          previewStates[messageID] == .disabled,
          let url = cachedURLs[messageID].flatMap(\.self),
          ImageURLClassifier.isImageURL(url) else { return }
    imageFetchTasks[messageID] = Task {
      await fetchInlineImage(for: messageID, url: url)
    }
  }

  /// Fetch inline image data and update state
  private func fetchInlineImage(for messageID: UUID, url: URL) async {
    let directURL = ImageURLClassifier.directImageURL(for: url)

    // Check malware domain blocklist before fetching
    if let host = directURL.host(), await MalwareDomainFilter.shared.isBlocked(host) {
      previewStates[messageID] = .malwareWarning
      rebuildDisplayItem(for: messageID)
      return
    }

    previewStates[messageID] = .loading
    rebuildDisplayItem(for: messageID)
    let result = await InlineImageCache.shared.fetchImageData(for: directURL)

    guard !Task.isCancelled else {
      imageFetchTasks.removeValue(forKey: messageID)
      return
    }
    guard itemIndexByID[messageID] != nil else {
      imageFetchTasks.removeValue(forKey: messageID)
      return
    }

    switch result {
    case let .loaded(data):
      let isGIF = ImageURLDetector.isGIFData(data)
      let entry: CachedDecodedImage? = await Task.detached { () -> CachedDecodedImage? in
        let decoded: UIImage? = isGIF
          ? ImageURLDetector.decodeGIFImage(from: data)
          : ImageURLDetector.downsampledImage(from: data)
        guard let decoded else { return nil }
        return CachedDecodedImage(
          image: decoded,
          isGIF: isGIF,
          data: isGIF ? nil : data
        )
      }.value
      // Persist before the cancellation/teardown guards so a
      // scroll-away or chat-exit mid-decode still surfaces the
      // pixels on the next chat re-entry.
      if let entry {
        InlineImageCache.shared.storeDecoded(entry, for: directURL)
      }
      guard !Task.isCancelled, let entry else {
        imageFetchTasks.removeValue(forKey: messageID)
        return
      }
      guard itemIndexByID[messageID] != nil else {
        imageFetchTasks.removeValue(forKey: messageID)
        return
      }
      applyDecodedImage(entry, for: messageID)

    case .loading:
      break

    case .notImage:
      // The URL is an HTML page, not an image. Record it so the build path
      // reroutes every message sharing it to the link-preview fragment, then
      // drop to idle so the cell's fetch task re-fires through the preview
      // path. With previews off the reroute renders text-only, so use a
      // terminal state that spends no fetch.
      imageURLsServingPages.insert(url.absoluteString)
      let rerouteState: PreviewLoadState = envInputs.previewsEnabled ? .idle : .noPreview
      previewStates[messageID] = rerouteState
      // A second loaded message sharing this URL hit the in-flight dedup and
      // only received .loading, leaving it shimmering as an inline image.
      // Reset and rebuild each such twin so the reroute's preview fetch
      // re-fires for it too.
      for (twinID, twinURL) in cachedURLs {
        guard twinID != messageID, twinURL == url,
              previewStates[twinID] == .loading else { continue }
        previewStates[twinID] = rerouteState
        rebuildDisplayItem(for: twinID)
      }

    case .failed:
      previewStates[messageID] = .noPreview
    }

    imageFetchTasks.removeValue(forKey: messageID)
    rebuildDisplayItem(for: messageID)
  }

  /// Cancel image fetch for a message
  func cancelImageFetch(for messageID: UUID) {
    imageFetchTasks[messageID]?.cancel()
    imageFetchTasks.removeValue(forKey: messageID)
  }

  /// Clean up image state for a specific message
  private func cleanupImageState(for messageID: UUID) {
    loadedImageData.removeObject(forKey: messageID as NSUUID)
    decodedImages.removeValue(forKey: messageID)
    imageIsGIF.removeValue(forKey: messageID)
    imageFetchTasks[messageID]?.cancel()
    imageFetchTasks.removeValue(forKey: messageID)
  }

  /// Clear all image state (called on conversation switch)
  private func clearImageState() {
    imageFetchTasks.values.forEach { $0.cancel() }
    imageFetchTasks.removeAll()
    loadedImageData.removeAllObjects()
    decodedImages.removeAll()
    imageIsGIF.removeAll()
  }
}
