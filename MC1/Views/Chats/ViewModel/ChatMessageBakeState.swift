import CoreLocation
import Foundation
import MC1Services
import UIKit

/// Per-message bake state that feeds `MessageBuildInputs`: preview/image
/// caches plus the New Messages divider pair. Not `@Observable` — redraw is
/// decided by the `Equatable MessageItem` (ref presence is encoded into the
/// item at every mutation site via a paired `rebuildDisplayItem`).
@MainActor
final class ChatMessageBakeState {
  /// Total cost limit for `loadedImageData`. `NSCache` evicts entries to
  /// stay under this byte budget and also responds to system memory
  /// pressure on its own.
  private static let imageDataCacheLimitBytes = 50 * 1024 * 1024

  /// Preview state per message (keyed by message ID)
  var previewStates: [UUID: PreviewLoadState] = [:]

  /// Loaded preview data per message (keyed by message ID)
  var loadedPreviews: [UUID: LinkPreviewDataDTO] = [:]

  /// Pre-decoded UIImage per message (avoids decoding in view body)
  var decodedImages: [UUID: UIImage] = [:]

  /// Pre-decoded link preview assets (single dictionary to batch updates)
  var decodedPreviewAssets: [UUID: DecodedPreviewAssets] = [:]

  /// Whether each image message is a GIF (computed once during decode)
  var imageIsGIF: [UUID: Bool] = [:]

  /// Raw image data per message (keyed by message ID). Backed by
  /// `NSCache` so memory pressure and the configured cost limit drive
  /// eviction instead of an unbounded dictionary.
  let loadedImageData: NSCache<NSUUID, NSData> = {
    let cache = NSCache<NSUUID, NSData>()
    cache.totalCostLimit = ChatMessageBakeState.imageDataCacheLimitBytes
    return cache
  }()

  /// Memoized `MessageText.buildFormattedText` output keyed by message ID.
  /// A message's text and direction are immutable, and every other
  /// formatting input is a function of `envInputs`, so an entry stays valid
  /// until the environment changes (which clears it). This turns a
  /// pagination rebuild from O(timeline) attributed-string work into
  /// O(new page); every already-loaded row is a cache hit.
  var formattedTextCache: [UUID: (text: AttributedString, mapCoordinate: CLLocationCoordinate2D?)] = [:]

  /// Cached URL detection results to avoid re-running NSDataDetector on rebuilds
  var cachedURLs: [UUID: URL?] = [:]

  /// Image-extension URLs the fetch path has discovered serve an HTML page,
  /// not image bytes (imgur, pasteboard, prnt.sc). Keyed by URL string so one
  /// discovery reroutes every loaded message sharing it. Gates the synchronous
  /// build path (`isInlineImageURL`, `shouldRequestImageFetch`); cleared on
  /// conversation switch, so re-entering a chat re-fetches each page URL once.
  var imageURLsServingPages: Set<String> = []

  /// Maps a snapshot request to the messages that show its thumbnail, so a late
  /// `resolutionStream` event rebuilds only those rows (O(matches)) instead of
  /// regex-scanning every loaded message. Populated in `makeBuildInputs`,
  /// cleared on conversation switch.
  var mapPreviewRequestIndex: [MapSnapshotRequest: Set<UUID>] = [:]

  /// Tracks in-flight legacy preview decode tasks to prevent duplicates
  var legacyPreviewDecodeInFlight: Set<UUID> = []

  /// Message ID that should show the "New Messages" divider above it
  var newMessagesDividerMessageID: UUID?

  /// Whether the divider position has been computed for the current conversation
  var dividerComputed = false

  private var inlineImageDimensionsStoreProvider: @MainActor () -> InlineImageDimensionsStore? = { nil }

  var inlineImageDimensionsStore: InlineImageDimensionsStore? {
    inlineImageDimensionsStoreProvider()
  }

  /// Rebinds the dimensions-store provider when live dependencies change.
  func bindInlineImageDimensionsStore(
    _ provider: @escaping @MainActor () -> InlineImageDimensionsStore?
  ) {
    inlineImageDimensionsStoreProvider = provider
  }
}
