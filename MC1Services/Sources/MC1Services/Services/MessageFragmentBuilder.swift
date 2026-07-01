import Foundation

/// Builds the immutable `[MessageFragment]` for a message from a `Sendable`
/// `MessageBuildInputs` snapshot. Pure function — no I/O, no async, no actor
/// state, no side effects. Unit-tested in isolation.
public enum MessageFragmentBuilder {
  /// Sentinel URL used when an inline image fragment falls into a `.failed`
  /// state without a real source URL. `about:blank` is RFC-defined and parses
  /// reliably, so the force-unwrap is safe; the bubble renders this as the
  /// generic failure placeholder.
  private static let blankURL = URL(string: "about:blank")!

  public static func makeItem(
    for message: MessageDTO,
    inputs: MessageBuildInputs,
    envInputs: EnvInputs
  ) -> MessageItem {
    MessageItem(
      id: message.id,
      envelope: makeEnvelope(for: message, inputs: inputs),
      content: makeFragments(for: message, inputs: inputs, envInputs: envInputs),
      footer: makeFooter(for: message, inputs: inputs, envInputs: envInputs),
      grouping: makeGrouping(inputs: inputs),
      shouldRequestPreviewFetch: shouldRequestPreviewFetch(
        inputs: inputs,
        message: message
      )
    )
  }

  public static func makeFragments(
    for message: MessageDTO,
    inputs: MessageBuildInputs,
    envInputs: EnvInputs
  ) -> [MessageFragment] {
    var fragments: [MessageFragment] = []

    fragments.append(.text(makeText(message: message, inputs: inputs, envInputs: envInputs)))

    if let summary = message.reactionSummary, !summary.isEmpty {
      fragments.append(.reactionSummary(summary))
    }

    let url = inputs.cachedURL
    let isImageURL = url.map(ImageURLClassifier.isImageURL) ?? false

    if inputs.previewState == .malwareWarning, let url {
      fragments.append(.malwareWarning(url))
      appendMapPreviewIfPresent(&fragments, inputs: inputs, envInputs: envInputs)
      return fragments
    }

    if isImageURL, envInputs.showInlineImages {
      fragments.append(.inlineImage(
        makeInlineImage(url: url, inputs: inputs, envInputs: envInputs)
      ))
    } else if envInputs.previewsEnabled {
      fragments.append(.linkPreview(
        makeLinkPreview(message: message, url: url, inputs: inputs)
      ))
    }

    appendMapPreviewIfPresent(&fragments, inputs: inputs, envInputs: envInputs)
    return fragments
  }

  /// Appends a `.mapPreview` for the first linkified coordinate, if any. Sibling
  /// order is deterministic (array order), so it sits after a link preview. The
  /// card is independent of any suspicious URL, so it is shown on malware
  /// messages too (the location is not the link).
  private static func appendMapPreviewIfPresent(
    _ fragments: inout [MessageFragment],
    inputs: MessageBuildInputs,
    envInputs: EnvInputs
  ) {
    // Privacy gate: when the user has disabled chat map thumbnails, skip the
    // fragment entirely so `MapPreviewFragmentView.onAppear` never fires the
    // third-party tile request. The coordinate text inside the message body
    // remains tappable through the formatted-text link path.
    guard envInputs.showMapPreviews else { return }
    guard let latitude = inputs.mapPreviewLatitude,
          let longitude = inputs.mapPreviewLongitude else { return }
    fragments.append(.mapPreview(MapPreviewFragmentState(
      latitude: latitude,
      longitude: longitude,
      isDark: envInputs.isDark,
      isOffline: envInputs.isOffline,
      isReady: inputs.isMapPreviewReady
    )))
  }

  private static func makeText(
    message: MessageDTO,
    inputs: MessageBuildInputs,
    envInputs: EnvInputs
  ) -> MessageTextPayload {
    MessageTextPayload(
      raw: message.text,
      formatted: inputs.formattedText,
      baseColor: inputs.baseColor,
      isOutgoing: message.isOutgoing,
      currentUserName: envInputs.currentUserName
    )
  }

  private static func makeInlineImage(
    url: URL?,
    inputs: MessageBuildInputs,
    envInputs: EnvInputs
  ) -> InlineImage {
    let loadState: InlineImage.LoadState = switch inputs.previewState {
    case .loaded:
      if inputs.hasInlineImageRef {
        .loaded(
          ImageReference(cacheKey: inputs.messageID, role: .inline),
          isGIF: inputs.imageIsGIF
        )
      } else if let url {
        .loading(url)
      } else {
        .failed(Self.blankURL)
      }
    case .loading:
      url.map { .loading($0) } ?? .failed(Self.blankURL)
    case .noPreview:
      url.map { .failed($0) } ?? .failed(Self.blankURL)
    case .idle, .disabled, .malwareWarning:
      url.map { .idle($0) } ?? .failed(Self.blankURL)
    }
    return InlineImage(
      state: loadState,
      autoPlayGIFs: envInputs.autoPlayGIFs,
      cachedAspect: inputs.inlineImageAspect
    )
  }

  private static func makeLinkPreview(
    message: MessageDTO,
    url: URL?,
    inputs: MessageBuildInputs
  ) -> LinkPreviewFragmentState {
    let imageRef = inputs.hasPreviewImageRef
      ? ImageReference(cacheKey: inputs.messageID, role: .linkPreviewImage)
      : nil
    let iconRef = inputs.hasPreviewIconRef
      ? ImageReference(cacheKey: inputs.messageID, role: .linkPreviewIcon)
      : nil

    let mode: LinkPreviewFragmentState.Mode = switch inputs.previewState {
    case .loaded:
      if let preview = inputs.loadedPreview {
        .loaded(preview, image: imageRef, icon: iconRef)
      } else {
        .noPreview
      }
    case .loading:
      url.map { .loading($0) } ?? .idle
    case .noPreview:
      .noPreview
    case .disabled:
      url.map { .disabled($0) } ?? .idle
    case .idle:
      if let urlString = message.linkPreviewURL,
         let legacyURL = URL(string: urlString) {
        .legacy(
          url: legacyURL,
          title: message.linkPreviewTitle,
          image: imageRef,
          icon: iconRef
        )
      } else if let url {
        .loading(url)
      } else {
        .idle
      }
    case .malwareWarning:
      .idle
    }
    return LinkPreviewFragmentState(mode: mode)
  }

  private static func makeEnvelope(
    for message: MessageDTO,
    inputs: MessageBuildInputs
  ) -> MessageEnvelope {
    MessageEnvelope(
      messageID: message.id,
      isOutgoing: message.isOutgoing,
      senderName: inputs.senderResolution.displayName,
      senderResolution: inputs.senderResolution,
      status: message.status,
      // Send time, not drain time: the centered divider is the sole time surface,
      // so a days-old drained message must not be relabeled at its delivery time.
      date: message.senderDate,
      hasFailed: message.hasFailed,
      containsSelfMention: message.containsSelfMention,
      mentionSeen: message.mentionSeen
    )
  }

  private static func makeFooter(
    for message: MessageDTO,
    inputs: MessageBuildInputs,
    envInputs: EnvInputs
  ) -> MessageFooter {
    let showHop = envInputs.showIncomingHopCount && message.isFloodRouted
    let region: String? = if envInputs.showIncomingRegion, message.isFloodRouted {
      message.regionScope
    } else {
      nil
    }
    // Send time shows on every incoming message (DM and channel) — unlike hop
    // and region, it is not gated on `isFloodRouted`. Shows the clock-corrected
    // `senderDate`, not the raw wire value, so a skewed sender clock doesn't put a
    // misleading timestamp in the bubble; the badge flags the substitution and the
    // raw value is available in the message info sheet.
    let sendTimeToShow: Date? =
      (envInputs.showIncomingSendTime && !message.isOutgoing)
        ? message.senderDate : nil
    return MessageFooter(
      showHop: showHop,
      hopCount: message.hopCount,
      formattedPath: inputs.formattedPath,
      regionToShow: region,
      sendTimeToShow: sendTimeToShow,
      sendTimeWasCorrected: message.timestampCorrected,
      showStatusRow: message.isOutgoing,
      status: message.status,
      isChannelMessage: message.isChannelMessage,
      heardRepeats: message.heardRepeats,
      retryAttempt: message.retryAttempt,
      maxRetryAttempts: message.maxRetryAttempts,
      sendCount: message.sendCount
    )
  }

  private static func makeGrouping(inputs: MessageBuildInputs) -> GroupingFlags {
    GroupingFlags(
      showTimestamp: inputs.showTimestamp,
      showDirectionGap: inputs.showDirectionGap,
      showSenderName: inputs.showSenderName,
      showNewMessagesDivider: inputs.showNewMessagesDivider,
      showDayDivider: inputs.showDayDivider
    )
  }

  /// Mirrors the predicate the bubble's `.onAppear` evaluated as
  /// `previewState == .idle && detectedURL != nil && message.linkPreviewURL == nil`.
  /// Pre-computed on the builder so the bubble body does not read view-model
  /// state during render.
  private static func shouldRequestPreviewFetch(
    inputs: MessageBuildInputs,
    message: MessageDTO
  ) -> Bool {
    inputs.previewState == .idle
      && inputs.cachedURL != nil
      && message.linkPreviewURL == nil
  }
}
