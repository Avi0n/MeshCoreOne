import MC1Services
import SwiftUI

/// Two-layer drop shadow shown only while the bubble is lifted: a tight contact
/// shadow under the bubble plus a softer ambient one for depth.
private let liftContactShadowOpacity: Double = 0.10
private let liftContactShadowRadius: CGFloat = 3
private let liftContactShadowYOffset: CGFloat = 1
private let liftAmbientShadowOpacity: Double = 0.20
private let liftAmbientShadowRadius: CGFloat = 12
private let liftAmbientShadowYOffset: CGFloat = 4

/// Minimum width reserved on the edge opposite a bubble, so a message never
/// spans the full row width and its alignment stays legible.
private let bubbleRowOppositeEdgeMinInset: CGFloat = 40

/// Unified message bubble for both direct and channel messages.
///
/// Conforms to `Equatable` with comparison on `item` alone. Closures
/// (`imageResolver`, `callbacks`) and constant chrome (`contactName`,
/// `deviceName`, `configuration`) are intentionally excluded; every
/// render-affecting input is encoded into `MessageItem` during
/// `rebuildDisplayItem`.
struct UnifiedMessageBubble: View, Equatable {
  let message: MessageDTO
  let contactName: String
  let deviceName: String
  let configuration: MessageBubbleConfiguration
  let item: MessageItem
  /// Box-vs-sibling partition derived once in `MessageBubbleView.body`.
  /// Excluded from `==` (which stays `item`-only): it is a pure function of
  /// `item.content`, so equal items yield equal layouts.
  let layout: FragmentLayout
  let imageResolver: (ImageReference) -> UIImage?
  let callbacks: MessageBubbleCallbacks

  @Environment(\.colorSchemeContrast) private var colorSchemeContrast
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.openURL) private var openURL
  @Environment(\.appTheme) private var theme

  @State private var showingReactionDetails = false
  @State private var isLongPressing = false
  @State private var longPressTrigger = 0

  nonisolated static func == (lhs: UnifiedMessageBubble, rhs: UnifiedMessageBubble) -> Bool {
    lhs.item == rhs.item
  }

  init(
    message: MessageDTO,
    contactName: String,
    deviceName: String = "Me",
    configuration: MessageBubbleConfiguration,
    item: MessageItem,
    layout: FragmentLayout,
    imageResolver: @escaping (ImageReference) -> UIImage? = { _ in nil },
    callbacks: MessageBubbleCallbacks = .init()
  ) {
    self.message = message
    self.contactName = contactName
    self.deviceName = deviceName
    self.configuration = configuration
    self.item = item
    self.layout = layout
    self.imageResolver = imageResolver
    self.callbacks = callbacks
  }

  var body: some View {
    VStack(spacing: 0) {
      if item.grouping.showNewMessagesDivider {
        NewMessagesDividerView()
          .padding(.bottom, 4)
      }

      if item.grouping.showDayDivider {
        MessageDayDividerView(date: item.envelope.date)
      }

      // The day divider already anchors the cluster in time, so a time-only
      // marker directly beneath it would be redundant; suppress it there.
      if item.grouping.showTimestamp, !item.grouping.showDayDivider {
        MessageTimestampView(date: item.envelope.date)
      }

      HStack(alignment: .bottom, spacing: 4) {
        if item.envelope.isOutgoing {
          Spacer(minLength: bubbleRowOppositeEdgeMinInset)
        }

        VStack(alignment: item.envelope.isOutgoing ? .trailing : .leading, spacing: 0) {
          if !item.envelope.isOutgoing,
             configuration.showSenderName,
             item.grouping.showSenderName {
            SenderNameLabel(resolution: item.envelope.senderResolution, nameColor: senderColor)
          }

          bubbleActionsLongPress(
            BubbleFragmentStack(
              item: item,
              layout: layout,
              bubbleColor: resolvedBubbleColor,
              callbacks: callbacks,
              imageResolver: imageResolver
            )
            .shadow(
              color: Color.black.opacity(isLongPressing ? liftContactShadowOpacity : 0),
              radius: liftContactShadowRadius,
              x: 0,
              y: liftContactShadowYOffset
            )
            .shadow(
              color: Color.black.opacity(isLongPressing ? liftAmbientShadowOpacity : 0),
              radius: liftAmbientShadowRadius,
              x: 0,
              y: liftAmbientShadowYOffset
            )
          )

          ForEach(Array(layout.siblings.enumerated()), id: \.offset) { _, fragment in
            siblingFragmentView(fragment)
          }

          if item.footer.showStatusRow {
            BubbleStatusRow(item: item, onRetry: callbacks.onRetry)
          }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityMessageLabel)
        .accessibilityAction {
          callbacks.onLongPress?()
        }
        .accessibilityActions {
          if item.footer.showStatusRow,
             item.footer.status == .failed,
             let onRetry = callbacks.onRetry {
            Button(L10n.Chats.Chats.Message.Action.retry) { onRetry() }
          }
          if hasReactionSummary {
            Button(L10n.Chats.Chats.Message.Action.viewReactions) {
              showingReactionDetails = true
            }
          }
          ForEach(MessageLinkAccessibility.actions(
            previewURL: linkPreviewURL,
            formatted: layout.textPayload?.formatted
          )) { action in
            Button(action.name) { openURL(action.url) }
          }
          if let inline = inlineImage {
            switch inline.state {
            case .loaded:
              if let onImageTap = callbacks.onImageTap {
                Button(L10n.Chats.Chats.Message.Action.viewImage) { onImageTap() }
              }
            case .failed:
              if let onRetryInlineImage = callbacks.onRetryInlineImage {
                Button(L10n.Chats.Chats.Message.Action.retryImage) { onRetryInlineImage() }
              }
            case .disabled:
              if let onManualPreviewFetch = callbacks.onManualPreviewFetch {
                Button(L10n.Chats.Chats.Preview.tapToLoad) { onManualPreviewFetch() }
              }
            case .loading, .idle:
              EmptyView()
            }
          }
        }
        .messageBubbleLongPressEffect(isPressing: isLongPressing, trigger: longPressTrigger)

        if !item.envelope.isOutgoing {
          Spacer(minLength: bubbleRowOppositeEdgeMinInset)
        }
      }
    }
    .padding(.horizontal, 12)
    .padding(.top, paddingTop)
    .padding(.bottom, 0)
    // Keyed on `previewFetchTaskID` rather than `.onAppear`: the URL that
    // satisfies the fetch precondition is detected asynchronously and lands
    // as a reconfigure after the cell has already appeared, so a one-shot
    // appear trigger never starts the fetch. The id is message-scoped so
    // cell reuse can't drop the trigger.
    .task(id: item.previewFetchTaskID) {
      if item.shouldRequestPreviewFetch {
        callbacks.onRequestPreviewFetch?()
      }
    }
    .sheet(isPresented: $showingReactionDetails) {
      ReactionDetailsSheet(messageID: message.id)
    }
  }

  /// Applies the bubble's actions-sheet long-press: a sustained press fires `onLongPress`, drives
  /// the lift, and bumps the haptic trigger. Shared by the box and the content-card siblings so a
  /// press anywhere on the bubble opens the same sheet.
  private func bubbleActionsLongPress(_ content: some View) -> some View {
    content.messageBubbleLongPressGesture(
      isPressing: $isLongPressing,
      trigger: $longPressTrigger,
      onFire: { callbacks.onLongPress?() }
    )
  }

  /// Renders one fragment from `layout.siblings` (reactions, malware warning, link preview, map
  /// preview). Content cards carry the bubble's long-press so a press anywhere opens the actions
  /// sheet; reactions keep their own. The text and inline-image kinds never reach the sibling list
  /// (they render inside `BubbleFragmentStack`), so their arm exists only to keep the switch
  /// exhaustive.
  @ViewBuilder
  private func siblingFragmentView(_ fragment: MessageFragment) -> some View {
    let content = siblingFragmentBody(fragment)
    if Self.siblingWantsActionsLongPress(fragment) {
      bubbleActionsLongPress(content)
    } else {
      content
    }
  }

  @ViewBuilder
  private func siblingFragmentBody(_ fragment: MessageFragment) -> some View {
    switch fragment {
    case let .reactionSummary(summary):
      ReactionsFragmentView(
        summary: summary,
        onTapReaction: { emoji in callbacks.onReaction?(emoji) },
        onLongPress: { showingReactionDetails = true }
      )
    case let .malwareWarning(url):
      MalwareWarningCard(url: url)
    case let .linkPreview(state):
      LinkPreviewFragmentView(
        state: state,
        imageResolver: imageResolver,
        onManualPreviewFetch: callbacks.onManualPreviewFetch
      )
    case let .mapPreview(state):
      MapPreviewFragmentView(
        state: state,
        snapshotResolver: { callbacks.snapshotResolver?($0) },
        onTap: { callbacks.onMapPreviewTap?($0) },
        onRequestSnapshot: { callbacks.requestSnapshot?($0) },
        onRetry: { callbacks.retrySnapshot?($0) }
      )
    case .text, .inlineImage:
      EmptyView()
    }
  }

  /// Whether a sibling fragment carries the bubble's actions-sheet long-press. Content cards
  /// (link, map, malware) do, so a press anywhere on the bubble opens the sheet. Reactions keep
  /// their own long-press; text and inline image render in the box, which already carries it.
  static func siblingWantsActionsLongPress(_ fragment: MessageFragment) -> Bool {
    switch fragment {
    case .linkPreview, .mapPreview, .malwareWarning:
      true
    case .reactionSummary, .text, .inlineImage:
      false
    }
  }

  // MARK: - Computed Properties

  private var resolvedBubbleColor: Color {
    if item.envelope.isOutgoing {
      if item.envelope.hasFailed {
        return AppColors.Message.outgoingBubbleFailed(
          highContrast: colorSchemeContrast == .increased
        )
      }
      return theme.accentColor
    }
    return theme.incomingBubbleColor
  }

  private var senderColor: Color {
    theme.identityColor(
      forName: item.envelope.senderName,
      colorScheme: colorScheme,
      contrast: colorSchemeContrast
    )
  }

  private var paddingTop: CGFloat {
    if item.grouping.showDirectionGap { return 6 }
    if item.grouping.showSenderName { return 4 }
    return item.envelope.isOutgoing ? 1 : 2
  }

  var accessibilityMessageLabel: String {
    var label = ""
    if !item.envelope.isOutgoing, configuration.showSenderName {
      let resolution = item.envelope.senderResolution
      if let nickname = resolution.unverifiedNickname {
        let rawName = L10n.Chats.Chats.Message.Sender.unverifiedNicknameFormat(item.envelope.senderName)
        label = "\(nickname) \(rawName): "
        label += "\(L10n.Chats.Chats.Message.Sender.unverifiedNicknameAccessibilityLabel), "
      } else {
        label = "\(item.envelope.senderName): "
        if resolution.isFallback {
          label += "\(L10n.Chats.Chats.Message.Sender.possibleMatch), "
        }
      }
    }
    label += message.text
    if item.envelope.isOutgoing {
      label += ", \(BubbleStatusRow.statusText(for: item))"
    }
    if !item.envelope.isOutgoing {
      if item.footer.showHop {
        label += ", \(L10n.Chats.Chats.Message.HopCount.accessibilityLabel(item.footer.hopCount))"
      }
      if let formattedPath = item.footer.formattedPath {
        label += ", \(L10n.Chats.Chats.Message.Path.accessibilityLabel(formattedPath))"
      }
      if let region = item.footer.regionToShow {
        label += ", \(L10n.Chats.Chats.Message.Region.accessibilityLabel(region))"
      }
    }
    return label
  }
}

private extension UnifiedMessageBubble {
  var inlineImage: InlineImage? {
    layout.inlineImage
  }

  var hasReactionSummary: Bool {
    layout.siblings.contains { if case .reactionSummary = $0 { true } else { false } }
  }

  var linkPreviewURL: URL? {
    for fragment in layout.siblings {
      if case let .linkPreview(state) = fragment {
        return state.primaryURL
      }
    }
    return nil
  }
}
