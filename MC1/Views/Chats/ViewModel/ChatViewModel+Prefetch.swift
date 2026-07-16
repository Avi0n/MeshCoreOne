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
  /// retried, or the message arrived during background sync.
  func handleDimensionResolution(_ url: URL) async {
    timeline.apply(.dimensionsResolved(url: url))
  }

  /// A snapshot finished: rebake only the rows that show it, found via the
  /// request-keyed index (O(matches)), never by scanning every loaded message.
  func handleSnapshotResolution(_ request: MapSnapshotRequest) {
    timeline.apply(.mapSnapshotResolved(request: request))
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
    recoverOrphanedLoadingState(for: messageID)
    guard bake.previewStates[messageID] == nil || bake.previewStates[messageID] == .idle else {
      // The cell asked for a fetch this view model considers settled: the
      // render item desynced from this state dictionary. Rebake the row so
      // it repairs in one frame instead of shimmering forever; loop-free
      // because `updateRenderItem` no-ops when the rebaked item is equal.
      rebuildDisplayItem(for: messageID)
      return
    }
    guard let url = bake.cachedURLs[messageID].flatMap(\.self) else { return }

    let isChannel = currentChannel != nil

    previewFetchTasks[messageID] = Task {
      await fetchPreview(for: messageID, url: url, isChannelMessage: isChannel)
    }
  }

  /// Fetch preview for a message and report the result into the timeline
  private func fetchPreview(for messageID: UUID, url: URL, isChannelMessage: Bool) async {
    guard let dataStore, let linkPreviewCache else { return }

    // Check malware domain blocklist before fetching
    if let host = url.host(), await MalwareDomainFilter.shared.isBlocked(host) {
      timeline.apply(.previewState(messageID: messageID, state: .malwareWarning))
      return
    }

    timeline.apply(.previewState(messageID: messageID, state: .loading))

    // Get preview from cache (handles all tiers: memory, database, network)
    let result = await linkPreviewCache.preview(
      for: url,
      using: dataStore,
      isChannelMessage: isChannelMessage
    )

    // Check if task was cancelled (message scrolled away or conversation changed)
    guard !Task.isCancelled else {
      previewFetchTasks.removeValue(forKey: messageID)
      timeline.apply(.resetOrphanedLoading(messageID: messageID))
      return
    }

    switch result {
    case let .loaded(dto):
      persistHeroAspect(from: dto, requestedURL: url)
      let assets = await decodePreviewAssets(from: dto)
      timeline.apply(.previewLoaded(messageID: messageID, preview: dto, assets: assets))

    case .loading:
      // Still loading (duplicate request), keep current state
      break

    case .noPreviewAvailable, .failed:
      timeline.apply(.previewState(messageID: messageID, state: .noPreview))

    case .disabled:
      timeline.apply(.previewState(messageID: messageID, state: .disabled))
    }

    previewFetchTasks.removeValue(forKey: messageID)
  }

  /// Manually fetch preview (for tap-to-load when previews disabled)
  func manualFetchPreview(for messageID: UUID) async {
    guard let url = bake.cachedURLs[messageID].flatMap(\.self),
          let dataStore,
          let linkPreviewCache else { return }

    timeline.apply(.previewState(messageID: messageID, state: .loading))

    let result = await linkPreviewCache.manualFetch(for: url, using: dataStore)

    switch result {
    case let .loaded(dto):
      persistHeroAspect(from: dto, requestedURL: url)
      let assets = await decodePreviewAssets(from: dto)
      timeline.apply(.previewLoaded(messageID: messageID, preview: dto, assets: assets))
    case .loading:
      break
    case .noPreviewAvailable, .failed, .disabled:
      timeline.apply(.previewState(messageID: messageID, state: .noPreview))
    }
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

  /// Decode preview hero image and icon off the main thread. Warms the
  /// process-lifetime cache before returning so a chat-exit mid-decode still
  /// surfaces the card on the next visit, and a later re-entry repaints
  /// loaded without re-decoding. Caches every resolved preview, including
  /// title-only cards with no hero or icon, so those skip the loading
  /// shimmer on re-entry too. Returns nil when there is nothing to bake.
  private func decodePreviewAssets(from dto: LinkPreviewDataDTO) async -> DecodedPreviewAssets? {
    async let heroResult: UIImage? = {
      guard let data = dto.imageData else { return nil }
      return await Task.detached { ImageURLDetector.downsampledImage(from: data) }.value
    }()
    async let iconResult: UIImage? = {
      guard let data = dto.iconData else { return nil }
      return await Task.detached { ImageURLDetector.downsampledImage(from: data) }.value
    }()
    let (hero, icon) = await (heroResult, iconResult)

    if let url = URL(string: dto.url) {
      DecodedPreviewCache.shared.store(
        CachedDecodedPreview(dto: dto, hero: hero, icon: icon),
        for: url
      )
    }

    guard hero != nil || icon != nil else { return nil }
    return DecodedPreviewAssets(image: hero, icon: icon)
  }

  /// Update a message in place and rebuild its display item.
  func updateMessage(id: UUID, mutation: (inout MessageDTO) -> Void) {
    timeline.updateMessage(id: id, mutation)
  }

  /// Rebuild a single MessageItem with current preview, image, and message
  /// state. No-ops when the message is no longer present.
  func rebuildDisplayItem(for messageID: UUID) {
    timeline.rebakeRow(messageID)
  }

  /// A `.loading` state with no in-flight task in either fetch table is
  /// orphaned: the task bailed on a path that never reset the state (a
  /// dedup follower's `.loading` result, a cancellation between state write
  /// and cleanup). Reset to `.idle` and rebake so the caller's fetch can
  /// re-fire and the item cannot strand at a shimmer the state has left
  /// behind; with a task genuinely in flight this is a no-op.
  private func recoverOrphanedLoadingState(for messageID: UUID) {
    guard previewFetchTasks[messageID] == nil,
          imageFetchTasks[messageID] == nil else { return }
    timeline.apply(.resetOrphanedLoading(messageID: messageID))
  }

  /// Cancel preview fetch for a message (called when cell scrolls away)
  func cancelPreviewFetch(for messageID: UUID) {
    previewFetchTasks[messageID]?.cancel()
    previewFetchTasks.removeValue(forKey: messageID)
  }

  /// Clear all preview and image state (called on conversation switch)
  func clearPreviewState() {
    previewFetchTasks.values.forEach { $0.cancel() }
    previewFetchTasks.removeAll()
    imageFetchTasks.values.forEach { $0.cancel() }
    imageFetchTasks.removeAll()
    timeline.clearBakeState()
  }

  /// Clean up preview and image state for a specific message (called on
  /// message deletion)
  func cleanupPreviewState(for messageID: UUID) {
    previewFetchTasks[messageID]?.cancel()
    previewFetchTasks.removeValue(forKey: messageID)
    imageFetchTasks[messageID]?.cancel()
    imageFetchTasks.removeValue(forKey: messageID)
    timeline.removeBakeState(for: messageID)
  }

  // MARK: - Inline Image State Management

  /// Returns the pre-decoded UIImage for a message, if available
  func decodedImage(for messageID: UUID) -> UIImage? {
    bake.decodedImages[messageID]
  }

  /// Returns the pre-decoded link preview hero image for a message
  func decodedPreviewImage(for messageID: UUID) -> UIImage? {
    bake.decodedPreviewAssets[messageID]?.image
  }

  /// Returns the pre-decoded link preview icon for a message
  func decodedPreviewIcon(for messageID: UUID) -> UIImage? {
    bake.decodedPreviewAssets[messageID]?.icon
  }

  /// Pre-decode images for legacy messages with embedded preview data
  func decodeLegacyPreviewImages() {
    for message in messages where message.linkPreviewURL != nil {
      let id = message.id
      let existing = bake.decodedPreviewAssets[id]
      let needsImageDecode = message.linkPreviewImageData != nil && existing?.image == nil
      let needsIconDecode = message.linkPreviewIconData != nil && existing?.icon == nil
      guard needsImageDecode || needsIconDecode,
            !bake.legacyPreviewDecodeInFlight.contains(id) else { continue }

      let imageData = message.linkPreviewImageData
      let iconData = message.linkPreviewIconData

      bake.legacyPreviewDecodeInFlight.insert(id)
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
          self?.timeline.apply(.previewAssetsDecoded(messageID: id, assets: DecodedPreviewAssets(image: hero, icon: icon)))
        }
        self?.bake.legacyPreviewDecodeInFlight.remove(id)
      }
    }
  }

  /// Returns whether the image for a message is a GIF
  func isGIFImage(for messageID: UUID) -> Bool {
    bake.imageIsGIF[messageID] ?? false
  }

  /// Returns the raw image data for a message, if available
  func imageData(for messageID: UUID) -> Data? {
    bake.loadedImageData.object(forKey: messageID as NSUUID).map { Data(referencing: $0) }
  }

  /// Clears the negative cache entry for a failed image and re-triggers the
  /// fetch. A visible retry is an explicit user action, so it bypasses the
  /// scope gate and fetches directly, same rationale as `manualFetchPreview`;
  /// routing back through `requestImageFetch` would bounce a scope-off failure
  /// to the tap-to-load placeholder instead of retrying.
  func retryImageFetch(for messageID: UUID) async {
    guard envInputs.previewsEnabled,
          bake.previewStates[messageID] != .malwareWarning else { return }
    guard let url = bake.cachedURLs[messageID].flatMap(\.self),
          ImageURLClassifier.isImageURL(url) else { return }

    let directURL = ImageURLClassifier.directImageURL(for: url)
    await InlineImageCache.shared.clearFailure(for: directURL)

    imageFetchTasks[messageID] = Task {
      await fetchInlineImage(for: messageID, url: url)
    }
  }

  /// Whether `url` routes to the inline-image fragment and fetch path.
  func routesToInlineImage(_ url: URL) -> Bool {
    bake.routesToInlineImage(url)
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
          let url = bake.cachedURLs[messageID].flatMap(\.self) else {
      return false
    }
    return routesToInlineImage(url)
  }

  /// Request inline image fetch for a message (called when cell becomes visible)
  func requestImageFetch(for messageID: UUID) {
    guard envInputs.previewsEnabled else { return }
    recoverOrphanedLoadingState(for: messageID)
    guard bake.previewStates[messageID] == nil || bake.previewStates[messageID] == .idle else {
      // Same self-heal as `requestPreviewFetch`: repair a desynced row
      // rather than leaving it shimmering.
      rebuildDisplayItem(for: messageID)
      return
    }
    guard let url = bake.cachedURLs[messageID].flatMap(\.self),
          ImageURLClassifier.isImageURL(url) else {
      return
    }

    // Master on but auto-resolve off for this conversation type: park the
    // state at `.disabled` so the cell renders the tap-to-load placeholder and
    // its `.task(id:)` re-fire loop terminates, mirroring the card path's
    // `LinkPreviewCache.preview` returning `.disabled`. No network fetch.
    guard linkPreviewPreferences.shouldAutoResolve(isChannelMessage: currentChannel != nil) else {
      timeline.apply(.previewState(messageID: messageID, state: .disabled))
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
          bake.previewStates[messageID] == .disabled,
          let url = bake.cachedURLs[messageID].flatMap(\.self),
          ImageURLClassifier.isImageURL(url) else { return }
    imageFetchTasks[messageID] = Task {
      await fetchInlineImage(for: messageID, url: url)
    }
  }

  /// Fetch inline image data and report the result into the timeline
  private func fetchInlineImage(for messageID: UUID, url: URL) async {
    let directURL = ImageURLClassifier.directImageURL(for: url)

    // Check malware domain blocklist before fetching
    if let host = directURL.host(), await MalwareDomainFilter.shared.isBlocked(host) {
      timeline.apply(.previewState(messageID: messageID, state: .malwareWarning))
      return
    }

    timeline.apply(.previewState(messageID: messageID, state: .loading))
    let result = await InlineImageCache.shared.fetchImageData(for: directURL)

    guard !Task.isCancelled else {
      imageFetchTasks.removeValue(forKey: messageID)
      timeline.apply(.resetOrphanedLoading(messageID: messageID))
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
        if Task.isCancelled {
          timeline.apply(.resetOrphanedLoading(messageID: messageID))
        } else if bake.previewStates[messageID] == .loading {
          // Undecodable bytes are terminal: retrying would loop on the
          // same cached data. Settle the row instead of stranding the
          // shimmer.
          timeline.apply(.previewState(messageID: messageID, state: .noPreview))
        }
        return
      }
      guard itemIndexByID[messageID] != nil else {
        imageFetchTasks.removeValue(forKey: messageID)
        return
      }
      timeline.apply(.imageDecoded(messageID: messageID, image: entry))

    case .loading:
      break

    case .notImage:
      // The URL is an HTML page, not an image. Reroute every message sharing
      // it to the link-preview fragment; with previews off the reroute
      // renders text-only, so use a terminal state that spends no fetch.
      let rerouteState: PreviewLoadState = envInputs.previewsEnabled ? .idle : .noPreview
      timeline.apply(.urlServesPage(messageID: messageID, url: url, reroute: rerouteState))

    case .failed:
      timeline.apply(.previewState(messageID: messageID, state: .noPreview))
    }

    imageFetchTasks.removeValue(forKey: messageID)
  }

  /// Cancel image fetch for a message
  func cancelImageFetch(for messageID: UUID) {
    imageFetchTasks[messageID]?.cancel()
    imageFetchTasks.removeValue(forKey: messageID)
  }
}
