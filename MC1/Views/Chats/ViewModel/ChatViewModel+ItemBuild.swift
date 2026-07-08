import CoreLocation
import Foundation
import MC1Services
import SwiftUI

extension ChatViewModel {
  // MARK: - Display Flags

  /// Time gap (in seconds) that breaks message grouping for timestamps and sender names.
  static let messageGroupingGapSeconds = 300

  /// Pre-computed display flags for a single message.
  struct DisplayFlags {
    let showTimestamp: Bool
    let showDirectionGap: Bool
    let showSenderName: Bool
    let showDayDivider: Bool
  }

  /// Computes all display flags in a single pass to avoid redundant message lookups.
  /// Used by buildItems() for O(n) performance instead of O(3n).
  static func computeDisplayFlags(for message: MessageDTO, previous: MessageDTO?) -> DisplayFlags {
    guard let previous else {
      return DisplayFlags(showTimestamp: true, showDirectionGap: false, showSenderName: true, showDayDivider: true)
    }

    // Keys on send time (senderDate), not the sortDate sort key. Under block-at-reconnect
    // a drained batch shares one sortDate, so grouping on it would collapse every in-block
    // divider; send time keeps honest separators inside the block. The fetch's timestamp
    // secondary key orders the block by send time, so headers stay monotonic within it.
    let timeGap = abs(Int(message.senderDate.timeIntervalSince(previous.senderDate)))

    // Keys on senderDate (the value the divider and time marker display) so the boundary
    // matches the times beneath it; a backlog synced in one session shares one receive
    // day but still divides on real send days.
    let dayChanged = !Calendar.current.isDate(message.senderDate, inSameDayAs: previous.senderDate)

    let showTimestamp = timeGap > messageGroupingGapSeconds
    let showDirectionGap = message.direction != previous.direction

    let showSenderName: Bool = if message.contactID != nil || message.isOutgoing {
      // UI suppresses the sender name for direct messages anyway; the branch
      // keeps the channel-message logic from running with a missing senderNodeName.
      true
    } else if previous.isOutgoing || timeGap > messageGroupingGapSeconds {
      true
    } else if let currentName = message.senderNodeName, let previousName = previous.senderNodeName {
      currentName != previousName
    } else {
      // No senderNodeName available on either side; show the name to be safe.
      true
    }

    return DisplayFlags(showTimestamp: showTimestamp, showDirectionGap: showDirectionGap, showSenderName: showSenderName, showDayDivider: dayChanged)
  }

  // MARK: - Item Build

  /// Assemble `MessageBuildInputs` from current view-model state. Reads
  /// `previewStates`, `cachedURLs`, `decodedImages`, etc. plus `envInputs`
  /// (`@MainActor` state). The returned snapshot is deterministic w.r.t. the
  /// inputs and `Sendable`, so it is safe to feed to the off-main builder. Has
  /// one `@MainActor` side effect: it records the map-preview snapshot request
  /// in `mapPreviewRequestIndex` so a late resolution can rebuild only the
  /// affected rows — hence it must be called on the main actor (the batch
  /// `buildItems()` path already does).
  func makeBuildInputs(for message: MessageDTO, previous: MessageDTO?) -> MessageBuildInputs {
    let flags = Self.computeDisplayFlags(for: message, previous: previous)
    let cachedURL = cachedURLs[message.id].flatMap(\.self)
    // Extension-based image classification, minus URLs the fetch path has
    // since discovered serve an HTML page. Computed once and reused for the
    // aspect-ratio gate so a rerouted URL neither fetches nor reserves a frame.
    let isInlineImageURL = cachedURL.map {
      ImageURLClassifier.isImageURL($0) && !imageURLsServingPages.contains($0.absoluteString)
    } ?? false
    let inlineImageAspect: Double? = {
      guard isInlineImageURL, let cachedURL,
            let store = inlineImageDimensionsStore else { return nil }
      let directURL = ImageURLClassifier.directImageURL(for: cachedURL)
      return store.aspect(for: directURL) ?? store.aspect(for: cachedURL)
    }()

    let theme = ThemeRegistry.theme(forID: envInputs.themeID) ?? .default
    let identityBackgroundLuminances = theme.avatarSurfaceLuminances(
      colorScheme: envInputs.isDark ? .dark : .light,
      contrast: envInputs.isHighContrast ? .increased : .standard
    )

    let formatted = MessageText.buildFormattedText(
      text: message.text,
      isOutgoing: message.isOutgoing,
      currentUserName: envInputs.currentUserName,
      isHighContrast: envInputs.isHighContrast,
      outgoingTextColor: theme.outgoingTextColor,
      hashtagColor: theme.hashtagColor,
      identityGamut: theme.identityGamut,
      identityBackgroundLuminances: identityBackgroundLuminances
    )

    var isMapPreviewReady = false
    // Gate the snapshot index on the privacy toggle so the index stays empty for
    // users who turned thumbnails off — `MessageFragmentBuilder` makes the same
    // check before appending the fragment, so the render request never fires.
    if envInputs.showMapPreviews, let coordinate = formatted.mapCoordinate {
      let request = MapSnapshotRequest(
        latitude: coordinate.latitude,
        longitude: coordinate.longitude,
        isDark: envInputs.isDark,
        isOffline: envInputs.isOffline
      )
      mapPreviewRequestIndex[request, default: []].insert(message.id)
      isMapPreviewReady = MapSnapshotStore.shared.isResolved(request)
    }

    return MessageBuildInputs(
      messageID: message.id,
      previewState: previewStates[message.id] ?? .idle,
      loadedPreview: loadedPreviews[message.id],
      cachedURL: cachedURL,
      isInlineImageURL: isInlineImageURL,
      hasInlineImageRef: decodedImages[message.id] != nil,
      hasPreviewImageRef: decodedPreviewAssets[message.id]?.image != nil,
      hasPreviewIconRef: decodedPreviewAssets[message.id]?.icon != nil,
      imageIsGIF: imageIsGIF[message.id] ?? false,
      inlineImageAspect: inlineImageAspect,
      mapPreviewLatitude: formatted.mapCoordinate?.latitude,
      mapPreviewLongitude: formatted.mapCoordinate?.longitude,
      isMapPreviewReady: isMapPreviewReady,
      formattedText: formatted.text,
      baseColor: message.isOutgoing ? .outgoing : .incoming,
      formattedPath: (envInputs.showIncomingPath && !message.isOutgoing)
        ? MessagePathFormatter.format(message)
        : nil,
      senderResolution: senderResolutionFor(message),
      showTimestamp: flags.showTimestamp,
      showDirectionGap: flags.showDirectionGap,
      showSenderName: flags.showSenderName,
      showNewMessagesDivider: message.id == newMessagesDividerMessageID,
      showDayDivider: flags.showDayDivider
    )
  }

  /// Single-message convenience that pairs `makeBuildInputs` with the pure
  /// `MessageFragmentBuilder`. Single-row callers (`appendMessageIfNew`,
  /// `rebuildDisplayItem`, `updateURLForDisplayItem`) keep using this; the
  /// batch path in `buildItems()` calls `makeBuildInputs` on main and then
  /// invokes the builder off-actor with the resulting snapshot.
  func makeItem(for message: MessageDTO, previous: MessageDTO?) -> MessageItem {
    MessageFragmentBuilder.makeItem(
      for: message,
      inputs: makeBuildInputs(for: message, previous: previous),
      envInputs: envInputs
    )
  }

  /// Recover the previous message in display order from the canonical
  /// `messages` array. Survives reordering side effects (e.g.,
  /// `reorderSameSenderClusters`) because it reads the current array at
  /// call time, not an item-index snapshot.
  func previousMessage(for messageID: UUID) -> MessageDTO? {
    guard let index = messages.firstIndex(where: { $0.id == messageID }),
          index > 0 else { return nil }
    return messages[index - 1]
  }

  /// Resolve a sender display name for a message. Channels run the
  /// contact-aware resolver; DMs fall back to the unknown sentinel
  /// because DM bubbles never display the sender row.
  ///
  /// Dispatch is keyed on `message.channelIndex` rather than the view
  /// model's `currentChannel`: `currentChannel` is nil during early
  /// rebuilds, so keying off it would mis-route channel rows to the
  /// DM path and bake the unknown sentinel into cached items.
  func senderResolutionFor(_ message: MessageDTO) -> NodeNameResolution {
    if message.channelIndex != nil {
      return MessageBubbleConfiguration.resolveSenderName(
        for: message,
        contacts: allContacts
      )
    }
    return NodeNameResolution(
      displayName: L10n.Chats.Chats.Message.Sender.unknown,
      matchKind: .unresolved
    )
  }
}
