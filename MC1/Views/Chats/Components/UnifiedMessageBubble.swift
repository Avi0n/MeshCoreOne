import SwiftUI
import MC1Services

/// Seconds the user must hold a bubble before the actions sheet is presented.
/// Long enough that a quick tap can never reach it, short enough that a
/// deliberate hold still feels responsive.
private let longPressConfirmDuration: Double = 0.6

/// Spring `response` (natural period) for the press-in/release scale animation.
private let longPressSpringResponse: Double = 0.7

/// Delay before the press-in lift animates, so a brief accidental tap shows no
/// lift. No delay is applied on release so the bubble retracts immediately.
private let longPressInDelay: Double = 0.1

/// Scale the bubble shrinks to while pressed, before the actions sheet opens.
private let longPressPressedScale: CGFloat = 0.95

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
    let imageResolver: (ImageReference) -> UIImage?
    let callbacks: MessageBubbleCallbacks

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.openURL) private var openURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
        imageResolver: @escaping (ImageReference) -> UIImage? = { _ in nil },
        callbacks: MessageBubbleCallbacks = .init()
    ) {
        self.message = message
        self.contactName = contactName
        self.deviceName = deviceName
        self.configuration = configuration
        self.item = item
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
            if item.grouping.showTimestamp && !item.grouping.showDayDivider {
                MessageTimestampView(date: item.envelope.date)
            }

            HStack(alignment: .bottom, spacing: 4) {
                if item.envelope.isOutgoing {
                    Spacer(minLength: bubbleRowOppositeEdgeMinInset)
                }

                VStack(alignment: item.envelope.isOutgoing ? .trailing : .leading, spacing: 0) {
                    if !item.envelope.isOutgoing
                        && configuration.showSenderName
                        && item.grouping.showSenderName {
                        HStack(spacing: 4) {
                            Text(item.envelope.senderName)
                                .font(.footnote)
                                .bold()
                                .foregroundStyle(senderColor)

                            if item.envelope.senderResolution.isFallback {
                                FallbackMatchIndicatorView()
                            }
                        }
                    }

                    BubbleFragmentStack(
                        item: item,
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
                    .onLongPressGesture(
                        minimumDuration: longPressConfirmDuration,
                        perform: {
                            longPressTrigger += 1
                            callbacks.onLongPress?()
                        },
                        onPressingChanged: { pressing in
                            isLongPressing = pressing
                        }
                    )

                    ForEach(Array(item.content.enumerated()), id: \.offset) { _, fragment in
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
                    if let url = linkPreviewURL {
                        Button(L10n.Chats.Chats.Message.Action.openLink) {
                            openURL(url)
                        }
                    }
                    if let inline = inlineImage {
                        switch inline.state {
                        case .loaded:
                            if let onImageTap = callbacks.onImageTap {
                                Button(L10n.Chats.Chats.Message.Action.viewImage) { onImageTap() }
                            }
                        case .failed:
                            if let onRetryImageFetch = callbacks.onRetryImageFetch {
                                Button(L10n.Chats.Chats.Message.Action.retryImage) { onRetryImageFetch() }
                            }
                        case .loading, .idle:
                            EmptyView()
                        }
                    }
                }
                .scaleEffect(isLongPressing ? longPressPressedScale : 1.0)
                .animation(
                    reduceMotion ? nil : .spring(response: longPressSpringResponse).delay(isLongPressing ? longPressInDelay : 0),
                    value: isLongPressing
                )
                .sensoryFeedback(.impact(flexibility: .solid), trigger: longPressTrigger)

                if !item.envelope.isOutgoing {
                    Spacer(minLength: bubbleRowOppositeEdgeMinInset)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, paddingTop)
        .padding(.bottom, 0)
        // Keyed on `shouldRequestPreviewFetch` rather than `.onAppear`: the URL
        // that satisfies the fetch precondition is detected asynchronously and
        // lands as a reconfigure after the cell has already appeared, so a
        // one-shot appear trigger never starts the fetch. `task(id:)` re-runs
        // when the flag flips, while staying lazy to cells in the view tree.
        .task(id: item.shouldRequestPreviewFetch) {
            if item.shouldRequestPreviewFetch {
                callbacks.onRequestPreviewFetch?()
            }
        }
        .sheet(isPresented: $showingReactionDetails) {
            ReactionDetailsSheet(messageID: message.id)
        }
    }

    /// Renders the sibling fragments that live below the colored bubble box:
    /// reactions, malware warning, link preview. Inline image fragments are
    /// rendered inside `BubbleFragmentStack` (attached to the bubble box) and
    /// are skipped here; the text fragment is also rendered inside the box.
    @ViewBuilder
    private func siblingFragmentView(_ fragment: MessageFragment) -> some View {
        switch fragment {
        case .reactionSummary(let summary):
            ReactionsFragmentView(
                summary: summary,
                onTapReaction: { emoji in callbacks.onReaction?(emoji) },
                onLongPress: { showingReactionDetails = true }
            )
        case .malwareWarning(let url):
            MalwareWarningCard(url: url)
        case .linkPreview(let state):
            LinkPreviewFragmentView(
                state: state,
                imageResolver: imageResolver,
                onManualPreviewFetch: callbacks.onManualPreviewFetch
            )
        case .mapPreview(let state):
            MapPreviewFragmentView(
                state: state,
                snapshotResolver: { MapSnapshotStore.shared.image(for: $0) },
                onTap: { callbacks.onMapPreviewTap?($0) },
                onRequestSnapshot: { MapSnapshotStore.shared.request($0) },
                onRetry: { MapSnapshotStore.shared.retry($0) }
            )
        case .text, .inlineImage:
            EmptyView()
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
        if !item.envelope.isOutgoing && configuration.showSenderName {
            label = "\(item.envelope.senderName): "
            if item.envelope.senderResolution.isFallback {
                label += "\(L10n.Chats.Chats.Message.Sender.possibleMatch), "
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
        for fragment in item.content {
            if case .inlineImage(let inline) = fragment { return inline }
        }
        return nil
    }

    var hasReactionSummary: Bool {
        for fragment in item.content {
            if case .reactionSummary = fragment { return true }
        }
        return false
    }

    var linkPreviewURL: URL? {
        for fragment in item.content {
            if case .linkPreview(let state) = fragment {
                switch state.mode {
                case .loaded(let dto, _, _):
                    return URL(string: dto.url)
                case .legacy(let url, _, _, _):
                    return url
                case .idle, .loading, .noPreview, .disabled:
                    return nil
                }
            }
        }
        return nil
    }
}
