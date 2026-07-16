import Foundation
import MC1Services

extension ChatTimeline {
  // MARK: - Bake updates

  /// A prefetch or resolution result reported into the timeline. Applying
  /// one mutates the bake state and rebakes the affected rows in the same
  /// call, so a result can never land without its paired redraw.
  enum BakeUpdate {
    /// Sets a message's preview load state (shimmer, malware warning,
    /// tap-to-load, terminal no-preview).
    case previewState(messageID: UUID, state: PreviewLoadState)
    /// A link-preview card resolved: DTO, decoded assets when present, and
    /// the `.loaded` transition land as one paint.
    case previewLoaded(messageID: UUID, preview: LinkPreviewDataDTO, assets: DecodedPreviewAssets?)
    /// An inline image finished decoding.
    case imageDecoded(messageID: UUID, image: CachedDecodedImage)
    /// Legacy embedded preview data finished decoding.
    case previewAssetsDecoded(messageID: UUID, assets: DecodedPreviewAssets)
    /// A URL classified as an image turned out to serve an HTML page:
    /// reroute this row and every loading twin sharing the URL.
    case urlServesPage(messageID: UUID, url: URL, reroute: PreviewLoadState)
    /// Return a row stuck at `.loading` with no live fetch to `.idle` so
    /// its fetch can re-arm; a no-op in any other state.
    case resetOrphanedLoading(messageID: UUID)
    /// Inline-image dimensions resolved for a URL: rebake every row whose
    /// body contains it so bubbles pick up the now-known aspect.
    case dimensionsResolved(url: URL)
    /// A map snapshot rendered: rebake the rows indexed under its request.
    case mapSnapshotResolved(request: MapSnapshotRequest)
  }

  /// Applies a prefetch/resolution result. The mutation and the rebake of
  /// every affected row happen inside this call.
  func apply(_ update: BakeUpdate) {
    switch update {
    case let .previewState(messageID, state):
      bake.previewStates[messageID] = state
      rebakeRow(messageID)

    case let .previewLoaded(messageID, dto, assets):
      if let assets {
        bake.decodedPreviewAssets[messageID] = assets
      }
      bake.previewStates[messageID] = .loaded
      bake.loadedPreviews[messageID] = dto
      rebakeRow(messageID)

    case let .imageDecoded(messageID, entry):
      bake.applyDecodedImage(entry, for: messageID)
      rebakeRow(messageID)

    case let .previewAssetsDecoded(messageID, assets):
      bake.decodedPreviewAssets[messageID] = assets
      rebakeRow(messageID)

    case let .urlServesPage(messageID, url, reroute):
      bake.imageURLsServingPages.insert(url.absoluteString)
      bake.previewStates[messageID] = reroute
      // A twin sharing this URL hit the in-flight dedup and only received
      // `.loading`; reroute it too so its preview fetch re-fires.
      for (twinID, twinURL) in bake.cachedURLs {
        guard twinID != messageID, twinURL == url,
              bake.previewStates[twinID] == .loading else { continue }
        bake.previewStates[twinID] = reroute
        rebakeRow(twinID)
      }
      rebakeRow(messageID)

    case let .resetOrphanedLoading(messageID):
      guard bake.previewStates[messageID] == .loading else { return }
      bake.previewStates[messageID] = .idle
      rebakeRow(messageID)

    case let .dimensionsResolved(url):
      let target = url.absoluteString
      for message in messages where message.text.contains(target) {
        rebakeRow(message.id)
      }

    case let .mapSnapshotResolved(request):
      guard let messageIDs = bake.mapPreviewRequestIndex[request] else { return }
      for messageID in messageIDs {
        rebakeRow(messageID)
      }
    }
  }

  /// Drops all per-message bake state (conversation switch). Fetch-task
  /// cancellation is the owner's job; this clears only what the bake holds.
  func clearBakeState() {
    bake.previewStates.removeAll()
    bake.loadedPreviews.removeAll()
    bake.decodedPreviewAssets.removeAll()
    bake.legacyPreviewDecodeInFlight.removeAll()
    bake.cachedURLs.removeAll()
    bake.imageURLsServingPages.removeAll()
    bake.mapPreviewRequestIndex.removeAll()
    bake.loadedImageData.removeAllObjects()
    bake.decodedImages.removeAll()
    bake.imageIsGIF.removeAll()
  }

  /// Drops one message's bake state (message deletion), including its
  /// map-preview index entries so a late snapshot resolution cannot rebake
  /// a row that no longer exists.
  func removeBakeState(for messageID: UUID) {
    bake.previewStates.removeValue(forKey: messageID)
    bake.loadedPreviews.removeValue(forKey: messageID)
    bake.decodedPreviewAssets.removeValue(forKey: messageID)
    bake.loadedImageData.removeObject(forKey: messageID as NSUUID)
    bake.decodedImages.removeValue(forKey: messageID)
    bake.imageIsGIF.removeValue(forKey: messageID)
    for request in Array(bake.mapPreviewRequestIndex.keys) {
      guard var ids = bake.mapPreviewRequestIndex[request], ids.remove(messageID) != nil else { continue }
      if ids.isEmpty {
        bake.mapPreviewRequestIndex.removeValue(forKey: request)
      } else {
        bake.mapPreviewRequestIndex[request] = ids
      }
    }
  }
}
