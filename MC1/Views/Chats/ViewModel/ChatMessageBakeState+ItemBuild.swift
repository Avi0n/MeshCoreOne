import CoreLocation
import Foundation
import MC1Services
import SwiftUI

extension ChatMessageBakeState {
  // MARK: - Display Flags

  /// Time gap (in seconds) that breaks message grouping for timestamps and sender names.
  static let messageGroupingGapSeconds = 300

  /// Unread count above which the "New Messages" divider is shown (strictly greater).
  /// Zero shows the divider for any unread backlog; when the unreads fit on one
  /// screen the open position clamps to the bottom with the divider line visible.
  private static let newMessagesDividerThreshold = 0

  /// Pre-computed display flags for a single message.
  struct DisplayFlags {
    let showTimestamp: Bool
    let showDirectionGap: Bool
    let showSenderName: Bool
    let showDayDivider: Bool
  }

  /// Computes all display flags in a single pass to avoid redundant message lookups.
  /// Used by `bakeAll` for O(n) performance instead of O(3n).
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

  /// Assemble `MessageBuildInputs` from current bake state. Reads
  /// `previewStates`, `cachedURLs`, `decodedImages`, etc. plus `envInputs`
  /// (`@MainActor` state). The returned snapshot is deterministic w.r.t. the
  /// inputs and `Sendable`, so it is safe to feed to the off-main builder. Has
  /// one `@MainActor` side effect: it records the map-preview snapshot request
  /// in `mapPreviewRequestIndex` so a late resolution can rebuild only the
  /// affected rows — hence it must be called on the main actor (the batch
  /// `bakeAll` path already does).
  func makeBuildInputs(
    for message: MessageDTO,
    previous: MessageDTO?,
    envInputs: EnvInputs,
    senderTables: ChatSenderTables
  ) -> MessageBuildInputs {
    seedPreviewStateIfNeeded(for: message, envInputs: envInputs)
    let flags = Self.computeDisplayFlags(for: message, previous: previous)
    let cachedURL = cachedURLs[message.id].flatMap(\.self)
    // Extension-based image classification, minus URLs the fetch path has
    // since discovered serve an HTML page. Computed once and reused for the
    // aspect-ratio gate so a rerouted URL neither fetches nor reserves a frame.
    let isInlineImageURL = cachedURL.map { routesToInlineImage($0) } ?? false
    let inlineImageAspect: Double? = {
      guard isInlineImageURL, let cachedURL,
            let store = inlineImageDimensionsStore else { return nil }
      let directURL = ImageURLClassifier.directImageURL(for: cachedURL)
      return store.aspect(for: directURL) ?? store.aspect(for: cachedURL)
    }()
    // Remembered hero size for the link-preview card, keyed by the page URL
    // (fetch paths persist it on every resolved preview). Distinct namespace
    // from the inline-image lookup above, which keys by the direct image URL.
    let previewHeroAspect: Double? = {
      guard !isInlineImageURL, let cachedURL,
            let store = inlineImageDimensionsStore else { return nil }
      return store.aspect(for: cachedURL)
    }()

    let formatted: (text: AttributedString, mapCoordinate: CLLocationCoordinate2D?)
    if let cached = formattedTextCache[message.id] {
      formatted = cached
    } else {
      let theme = ThemeRegistry.theme(forID: envInputs.themeID) ?? .default
      let identityBackgroundLuminances = theme.avatarSurfaceLuminances(
        colorScheme: envInputs.isDark ? .dark : .light,
        contrast: envInputs.isHighContrast ? .increased : .standard
      )
      formatted = MessageText.buildFormattedText(
        text: message.text,
        isOutgoing: message.isOutgoing,
        currentUserName: envInputs.currentUserName,
        isHighContrast: envInputs.isHighContrast,
        outgoingTextColor: theme.outgoingTextColor,
        hashtagColor: theme.hashtagColor,
        identityGamut: theme.identityGamut,
        identityBackgroundLuminances: identityBackgroundLuminances
      )
      formattedTextCache[message.id] = formatted
    }

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
      previewHeroAspect: previewHeroAspect,
      mapPreviewLatitude: formatted.mapCoordinate?.latitude,
      mapPreviewLongitude: formatted.mapCoordinate?.longitude,
      isMapPreviewReady: isMapPreviewReady,
      formattedText: formatted.text,
      baseColor: message.isOutgoing ? .outgoing : .incoming,
      formattedPath: (envInputs.showIncomingPath && !message.isOutgoing)
        ? MessagePathFormatter.format(message)
        : nil,
      senderResolution: senderResolutionFor(message, senderTables: senderTables),
      showTimestamp: flags.showTimestamp,
      showDirectionGap: flags.showDirectionGap,
      showSenderName: flags.showSenderName,
      showNewMessagesDivider: message.id == newMessagesDividerMessageID,
      showDayDivider: flags.showDayDivider
    )
  }

  /// Whether `url` routes to the inline-image fragment and fetch path: an
  /// image-extension or resolvable URL the fetch path has not since found to
  /// serve an HTML page. `imageURLsServingPages` covers reroutes discovered
  /// this session; `InlineImageCache.shared.servesHTMLPage` carries the verdict across
  /// chat re-entry so a reloaded card is not re-classified as a stranded
  /// inline-image shimmer.
  func routesToInlineImage(_ url: URL) -> Bool {
    guard ImageURLClassifier.isImageURL(url),
          !imageURLsServingPages.contains(url.absoluteString) else { return false }
    return !InlineImageCache.shared.servesHTMLPage(ImageURLClassifier.directImageURL(for: url))
  }

  /// Resolve a sender display name for a message. Channels run the
  /// contact-aware resolver; DMs fall back to the unknown sentinel
  /// because DM bubbles never display the sender row.
  ///
  /// Dispatch is keyed on `message.channelIndex` rather than a conversation
  /// context flag: a nil channel context during early rebuilds would
  /// mis-route channel rows to the DM path and bake the unknown sentinel
  /// into cached items.
  func senderResolutionFor(
    _ message: MessageDTO,
    senderTables: ChatSenderTables
  ) -> NodeNameResolution {
    if message.channelIndex != nil {
      return MessageBubbleConfiguration.resolveSenderName(
        for: message,
        contacts: senderTables.contacts,
        nicknamesByLoweredName: senderTables.nicknamesByLoweredName
      )
    }
    return NodeNameResolution(
      displayName: L10n.Chats.Chats.Message.Sender.unknown,
      matchKind: .unresolved
    )
  }

  // MARK: - Divider

  /// Computes the divider message ID from a fetched (unfiltered) message array.
  /// Must be called before filtering. Sets `dividerComputed = true`.
  ///
  /// Positional: the divider sits `unreadCount` rows from the end. This relies on unread
  /// messages occupying the array tail, which block-at-reconnect upholds — every unread row
  /// (live or drained) takes a sortDate at or after its receive/drain time, later than any
  /// already-read row, so unread always sorts to the tail. Do not switch this to a
  /// `first(where: { !$0.isRead })` scan: per-message `isRead` is not maintained on chat open
  /// (only the unread counter is cleared), so the scan would land on the first row of the page.
  ///
  /// The boundary row may be a sent outgoing reaction that `filterOutgoingReactionMessages`
  /// drops before the items are built; the divider id must survive that filter, so advance
  /// past any hidden row to the next visible one (toward newer), which renders at the same
  /// visual position.
  func computeDividerPosition(from messages: [MessageDTO], unreadCount: Int, isDM: Bool) {
    guard !dividerComputed, unreadCount > Self.newMessagesDividerThreshold else { return }
    var dividerIndex = max(0, messages.count - unreadCount)
    while dividerIndex < messages.count, isHiddenOutgoingReaction(messages[dividerIndex], isDM: isDM) {
      dividerIndex += 1
    }
    if dividerIndex < messages.count {
      newMessagesDividerMessageID = messages[dividerIndex].id
    }
    dividerComputed = true
  }

  // MARK: - Reaction Filtering

  /// Filter out outgoing reaction messages unless they failed to send.
  /// Reaction messages are hidden from the UI to avoid clutter since they're displayed as badges.
  /// - Parameters:
  ///   - messages: The messages to filter
  ///   - isDM: Whether these are DM messages (uses parseDM) or channel messages (uses parse)
  /// - Returns: Filtered messages with successful outgoing reactions removed
  func filterOutgoingReactionMessages(_ messages: [MessageDTO], isDM: Bool) -> [MessageDTO] {
    messages.filter { !isHiddenOutgoingReaction($0, isDM: isDM) }
  }

  /// Whether a message is a successfully-sent outgoing reaction, which is rendered
  /// as a badge and so hidden from the timeline by `filterOutgoingReactionMessages`.
  /// Failed reactions stay visible so the user can retry them.
  func isHiddenOutgoingReaction(_ message: MessageDTO, isDM: Bool) -> Bool {
    guard message.direction == .outgoing else { return false }

    let isReaction = isDM
      ? ReactionParser.parseDM(message.text) != nil
      : ReactionParser.parse(message.text) != nil

    guard isReaction else { return false }

    return message.status != .failed
  }

  // MARK: - Batch Bake

  /// Build `MessageItem`s for `messages` with pre-computed properties.
  /// Clears the map-preview request index and prunes `formattedTextCache`
  /// against live IDs so populate-driven reloads get the same hygiene as
  /// view-driven rebuilds. Snapshots bake state on the main actor and
  /// delegates the per-message builder loop to `writer.rebuildItems`.
  func bakeAll(
    messages: [MessageDTO],
    writer: ChatTimelineWriter,
    envInputs: EnvInputs,
    senderTables: ChatSenderTables,
    postApply: (@MainActor () -> Void)?
  ) {
    // Drop stale entries from the previous build before `makeBuildInputs`
    // re-inserts. Theme toggle and offline-state flip both rebuild items
    // under a new request key for the same message; without this, the old
    // key's bucket lingers and a late resolution could rebuild a row whose
    // current request key has changed.
    mapPreviewRequestIndex.removeAll()

    // Drop formatted-text entries for messages no longer in the timeline
    // (conversation switch, deletion). Guarded so it is a no-op during normal
    // pagination, where the cache only ever grows toward the message count.
    if formattedTextCache.count > messages.count {
      let liveIDs = Set(messages.map(\.id))
      formattedTextCache = formattedTextCache.filter { liveIDs.contains($0.key) }
    }

    // URL detection and decoded-cache rehydration run synchronously inside
    // `makeBuildInputs` (see `seedPreviewStateIfNeeded`), so every row leaves
    // this loop already carrying its preview fragment at a stable height.
    let inputs: [(MessageDTO, MessageBuildInputs)] = messages.enumerated().map { index, message in
      let previous: MessageDTO? = index > 0 ? messages[index - 1] : nil
      return (
        message,
        makeBuildInputs(
          for: message,
          previous: previous,
          envInputs: envInputs,
          senderTables: senderTables
        )
      )
    }

    writer.rebuildItems(inputs: inputs, envInputs: envInputs, postApply: postApply)
  }
}
