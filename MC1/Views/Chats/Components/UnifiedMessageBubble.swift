import SwiftUI
import MC1Services

/// Unified message bubble for both direct and channel messages
struct UnifiedMessageBubble: View {
    let message: MessageDTO
    let contactName: String
    let deviceName: String
    let configuration: MessageBubbleConfiguration
    let displayState: MessageDisplayState
    let callbacks: MessageBubbleCallbacks

    @AppStorage("linkPreviewsEnabled") private var previewsEnabled = false
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    @State private var showingReactionDetails = false
    @State private var longPressTriggered = false

    init(
        message: MessageDTO,
        contactName: String,
        deviceName: String = "Me",
        configuration: MessageBubbleConfiguration,
        displayState: MessageDisplayState = .init(),
        callbacks: MessageBubbleCallbacks = .init()
    ) {
        self.message = message
        self.contactName = contactName
        self.deviceName = deviceName
        self.configuration = configuration
        self.displayState = displayState
        self.callbacks = callbacks
    }

    var body: some View {
        VStack(spacing: 0) {
            if displayState.showNewMessagesDivider {
                NewMessagesDividerView()
                    .padding(.bottom, 4)
            }

            // Centered timestamp (iMessage-style)
            if displayState.showTimestamp {
                MessageTimestampView(date: message.date)
            }

            // Bubble content (aligned based on direction)
            HStack(alignment: .bottom, spacing: 4) {
                if message.isOutgoing {
                    Spacer(minLength: 40)
                }

                VStack(alignment: message.isOutgoing ? .trailing : .leading, spacing: 0) {
                    // Sender name for incoming channel messages (hidden for continuation messages in a group)
                    if !message.isOutgoing && configuration.showSenderName && displayState.showSenderName {
                        Text(senderName)
                            .font(.footnote)
                            .bold()
                            .foregroundStyle(senderColor)
                    }

                    // Message bubble with text and optional routing footer
                    BubbleContent(
                        message: message,
                        deviceName: deviceName,
                        displayState: displayState,
                        callbacks: callbacks
                    )
                    .onLongPressGesture(minimumDuration: 0.3) {
                        longPressTriggered.toggle()
                        callbacks.onLongPress?()
                    }
                    .sensoryFeedback(.impact(weight: .medium), trigger: longPressTriggered)

                    // Reaction badges (for messages with reactions)
                    if let summary = message.reactionSummary, !summary.isEmpty {
                        ReactionBadgesView(
                            summary: summary,
                            onTapReaction: { emoji in
                                callbacks.onReaction?(emoji)
                            },
                            onLongPress: {
                                showingReactionDetails = true
                            }
                        )
                        .offset(y: -6)
                        .padding(.bottom, -6)
                    }

                    // Malware warning (always shown, regardless of preview settings)
                    if displayState.previewState == .malwareWarning,
                       let url = displayState.detectedURL {
                        MalwareWarningCard(url: url)
                    }

                    // Link preview (if applicable, skip for image URLs shown in bubble)
                    if previewsEnabled && !(displayState.isImageURL && displayState.showInlineImages) {
                        BubbleLinkPreviewContent(
                            message: message,
                            displayState: displayState,
                            onManualPreviewFetch: callbacks.onManualPreviewFetch
                        )
                    }

                    // Status row for outgoing messages
                    if message.isOutgoing {
                        BubbleStatusRow(
                            message: message,
                            onRetry: callbacks.onRetry
                        )
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(accessibilityMessageLabel)

                if !message.isOutgoing {
                    Spacer(minLength: 40)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, displayState.showDirectionGap ? 6 : (displayState.showSenderName ? 4 : (message.isOutgoing ? 1 : 2)))
        .padding(.bottom, 0)
        .onAppear {
            // Request preview/image fetch when cell becomes visible
            // ViewModel handles deduplication and cancellation
            if displayState.previewState == .idle && displayState.detectedURL != nil && message.linkPreviewURL == nil {
                callbacks.onRequestPreviewFetch?()
            }
        }
        .sheet(isPresented: $showingReactionDetails) {
            ReactionDetailsSheet(messageID: message.id)
        }
    }

    // MARK: - Computed Properties

    private var senderName: String {
        configuration.senderNameResolver?(message) ?? L10n.Chats.Chats.Message.Sender.unknown
    }

    private var senderColor: Color {
        AppColors.NameColor.color(for: senderName, highContrast: colorSchemeContrast == .increased)
    }

    private var accessibilityMessageLabel: String {
        var label = ""
        // Always include sender name for screen readers, even when visually hidden
        if !message.isOutgoing && configuration.showSenderName {
            label = "\(senderName): "
        }
        label += message.text
        if message.isOutgoing {
            label += ", \(BubbleStatusRow.statusText(for: message))"
        }
        if !message.isOutgoing {
            if displayState.showIncomingHopCount, message.isFloodRouted {
                label += ", \(L10n.Chats.Chats.Message.HopCount.accessibilityLabel(message.hopCount))"
            }
            if let formattedPath = displayState.formattedPath {
                label += ", \(L10n.Chats.Chats.Message.Path.accessibilityLabel(formattedPath))"
            }
            if displayState.showIncomingRegion, message.isFloodRouted, let region = message.regionScope {
                label += ", \(L10n.Chats.Chats.Message.Region.accessibilityLabel(region))"
            }
        }
        return label
    }

    // MARK: - Helpers

}

// MARK: - Extracted Views

private struct BubbleContent: View {
    let message: MessageDTO
    let deviceName: String
    let displayState: MessageDisplayState
    let callbacks: MessageBubbleCallbacks

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var textColor: Color {
        message.isOutgoing ? .white : .primary
    }

    private var bubbleColor: Color {
        if message.isOutgoing {
            return message.hasFailed ? AppColors.Message.outgoingBubbleFailed : AppColors.Message.outgoingBubble
        } else {
            return AppColors.Message.incomingBubble
        }
    }

    private var predicates: MessageBubblePredicates {
        MessageBubblePredicates(message: message, displayState: displayState)
    }

    @ViewBuilder
    private func footerContent(allowsWrap: Bool) -> some View {
        let p = predicates
        if p.showHop {
            BubbleHopCountFooter(hopCount: message.hopCount)
        }
        if let formattedPath = displayState.formattedPath {
            BubblePathFooter(formattedPath: formattedPath)
        }
        if let region = p.regionToShow {
            BubbleRegionFooter(regionName: region, allowsWrap: allowsWrap)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                MessageText(message.text, baseColor: textColor, isOutgoing: message.isOutgoing, currentUserName: deviceName, precomputedText: displayState.formattedText)

                if !message.isOutgoing && predicates.hasFooter {
                    if dynamicTypeSize.isAccessibilitySize {
                        VStack(alignment: .leading, spacing: 2) {
                            footerContent(allowsWrap: true)
                        }
                    } else {
                        HStack(spacing: 4) {
                            footerContent(allowsWrap: false)
                        }
                    }
                }
            }
            .bubbleContentPadding()

            if displayState.isImageURL && displayState.showInlineImages {
                BubbleEmbeddedImageContent(
                    message: message,
                    displayState: displayState,
                    callbacks: callbacks
                )
            }
        }
        .background(bubbleColor)
        .clipShape(.rect(cornerRadius: 16))
    }
}

private struct BubbleEmbeddedImageContent: View {
    let message: MessageDTO
    let displayState: MessageDisplayState
    let callbacks: MessageBubbleCallbacks

    var body: some View {
        switch displayState.previewState {
        case .loaded:
            if let image = displayState.decodedImage {
                InlineImageView(
                    image: image,
                    isGIF: displayState.isGIF,
                    autoPlayGIFs: displayState.autoPlayGIFs,
                    isEmbedded: true,
                    onTap: { callbacks.onImageTap?() }
                )
                .frame(maxWidth: .infinity)
            }

        case .loading, .idle:
            if displayState.detectedURL != nil {
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                        .tint(message.isOutgoing ? .white.opacity(0.7) : nil)
                    Text(L10n.Chats.Chats.Preview.loading)
                        .font(.subheadline)
                        .foregroundStyle(message.isOutgoing ? .white.opacity(0.7) : .secondary)
                }
                .bubbleContentPadding()
            }

        case .noPreview, .disabled:
            if displayState.isImageURL && displayState.showInlineImages && displayState.previewState == .noPreview {
                Button(action: { callbacks.onRetryImageFetch?() }) {
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.clockwise")
                            .foregroundStyle(message.isOutgoing ? .white.opacity(0.7) : .secondary)
                        Text(L10n.Chats.Chats.InlineImage.tapToRetry)
                            .font(.subheadline)
                            .foregroundStyle(message.isOutgoing ? .white.opacity(0.7) : .secondary)
                    }
                    .bubbleContentPadding()
                }
                .buttonStyle(.plain)
                .accessibilityHint(L10n.Chats.Chats.InlineImage.retryHint)
            }

        case .malwareWarning:
            EmptyView()
        }
    }
}

private struct BubbleLinkPreviewContent: View {
    let message: MessageDTO
    let displayState: MessageDisplayState
    let onManualPreviewFetch: (() -> Void)?

    @Environment(\.openURL) private var openURL

    var body: some View {
        switch displayState.previewState {
        case .loaded:
            if let preview = displayState.loadedPreview,
               let url = URL(string: preview.url) {
                LinkPreviewCard(
                    url: url,
                    title: preview.title,
                    image: displayState.decodedPreviewImage,
                    icon: displayState.decodedPreviewIcon,
                    onTap: { openURL(url) }
                )
            }

        case .loading:
            if let url = displayState.detectedURL {
                LinkPreviewLoadingCard(url: url)
            }

        case .noPreview:
            EmptyView()

        case .disabled:
            if let url = displayState.detectedURL {
                TapToLoadPreview(
                    url: url,
                    isLoading: false,
                    onTap: {
                        onManualPreviewFetch?()
                    }
                )
            }

        case .idle:
            // Check for legacy message data
            if let urlString = message.linkPreviewURL,
               let url = URL(string: urlString) {
                LinkPreviewCard(
                    url: url,
                    title: message.linkPreviewTitle,
                    image: displayState.decodedPreviewImage,
                    icon: displayState.decodedPreviewIcon,
                    onTap: { openURL(url) }
                )
            } else if let url = displayState.detectedURL {
                // URL detected, waiting for fetch - show loading
                LinkPreviewLoadingCard(url: url)
            }

        case .malwareWarning:
            EmptyView()
        }
    }
}

private struct BubbleStatusRow: View {
    let message: MessageDTO
    let onRetry: (() -> Void)?

    var body: some View {
        HStack(spacing: 4) {
            // Only show retry button for failed messages (not retrying)
            if message.status == .failed, let onRetry {
                Button {
                    onRetry()
                } label: {
                    HStack(spacing: 2) {
                        Image(systemName: "arrow.clockwise")
                        Text(L10n.Chats.Chats.Message.Status.retry)
                    }
                    .font(.caption2)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.blue)
            }

            // Only show icon for failed status
            if message.status == .failed {
                Image(systemName: "exclamationmark.circle")
                    .font(.caption2)
                    .foregroundStyle(.red)
            }

            Text(Self.statusText(for: message))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(.trailing, 4)
    }

    static func statusText(for message: MessageDTO) -> String {
        switch message.status {
        case .pending, .sending:
            return L10n.Chats.Chats.Message.Status.sending
        case .sent:
            // Build status parts: repeats, send count, sent
            var parts: [String] = []
            if message.heardRepeats > 0 {
                let repeatWord = message.heardRepeats == 1
                    ? L10n.Chats.Chats.Message.Repeat.singular
                    : L10n.Chats.Chats.Message.Repeat.plural
                parts.append("\(message.heardRepeats) \(repeatWord)")
            }
            if message.sendCount > 1 {
                parts.append(L10n.Chats.Chats.Message.Status.sentMultiple(message.sendCount))
            } else {
                parts.append(L10n.Chats.Chats.Message.Status.sent)
            }
            return parts.joined(separator: " • ")
        case .delivered:
            if message.heardRepeats > 0 {
                let repeatWord = message.heardRepeats == 1
                    ? L10n.Chats.Chats.Message.Repeat.singular
                    : L10n.Chats.Chats.Message.Repeat.plural
                let repeatText = "\(message.heardRepeats) \(repeatWord)"
                return "\(repeatText) • \(L10n.Chats.Chats.Message.Status.delivered)"
            }
            return L10n.Chats.Chats.Message.Status.delivered
        case .failed:
            return L10n.Chats.Chats.Message.Status.failed
        case .retrying:
            // Show attempt count: "Retrying 1/4" (1-indexed for user display)
            let displayAttempt = message.retryAttempt + 1
            let maxAttempts = message.maxRetryAttempts
            if maxAttempts > 0 {
                return L10n.Chats.Chats.Message.Status.retryingAttempt(displayAttempt, maxAttempts)
            }
            return L10n.Chats.Chats.Message.Status.retrying
        }
    }
}

private struct BubblePathFooter: View {
    let formattedPath: String

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
            Text(formattedPath)
        }
        .font(.caption2.monospaced())
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(L10n.Chats.Chats.Message.Path.accessibilityLabel(formattedPath))
    }
}

private struct BubbleHopCountFooter: View {
    let hopCount: Int

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "arrowshape.bounce.right")
            Text("\(hopCount)")
        }
        .font(.caption2)  // Not monospaced - only hex paths need alignment
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(L10n.Chats.Chats.Message.HopCount.accessibilityLabel(hopCount))
    }
}

private struct BubbleRegionFooter: View {
    let regionName: String
    let allowsWrap: Bool

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "globe")
            Text(regionName)
                .lineLimit(allowsWrap ? nil : 1)
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(L10n.Chats.Chats.Message.Region.accessibilityLabel(regionName))
    }
}

// MARK: - Helpers

private extension View {
    func bubbleContentPadding() -> some View {
        padding(.horizontal, 10)
            .padding(.vertical, 8)
    }
}

// MARK: - Previews

#Preview("Direct - Outgoing Sent") {
    let message = Message(
        radioID: UUID(),
        contactID: UUID(),
        text: "Hello! How are you doing today?",
        directionRawValue: MessageDirection.outgoing.rawValue,
        statusRawValue: MessageStatus.sent.rawValue
    )
    return UnifiedMessageBubble(
        message: MessageDTO(from: message),
        contactName: "Alice",
        deviceName: "My Device",
        configuration: .directMessage
    )
}

#Preview("Direct - Outgoing Delivered") {
    let message = Message(
        radioID: UUID(),
        contactID: UUID(),
        text: "This message was delivered successfully!",
        directionRawValue: MessageDirection.outgoing.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue,
        roundTripTime: 1234,
        heardRepeats: 2
    )
    return UnifiedMessageBubble(
        message: MessageDTO(from: message),
        contactName: "Bob",
        deviceName: "My Device",
        configuration: .directMessage
    )
}

#Preview("Direct - Outgoing Failed") {
    let message = Message(
        radioID: UUID(),
        contactID: UUID(),
        text: "This message failed to send",
        directionRawValue: MessageDirection.outgoing.rawValue,
        statusRawValue: MessageStatus.failed.rawValue
    )
    return UnifiedMessageBubble(
        message: MessageDTO(from: message),
        contactName: "Charlie",
        deviceName: "My Device",
        configuration: .directMessage,
        callbacks: MessageBubbleCallbacks(onRetry: { })
    )
}

#Preview("Channel - Public Incoming") {
    let message = Message(
        radioID: UUID(),
        channelIndex: 1,
        text: "Hello from the public channel!",
        directionRawValue: MessageDirection.incoming.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue,
        senderNodeName: "RemoteNode"
    )
    return UnifiedMessageBubble(
        message: MessageDTO(from: message),
        contactName: "General",
        deviceName: "My Device",
        configuration: .channel(isPublic: true, contacts: [])
    )
}

#Preview("Channel - Private Outgoing") {
    let message = Message(
        radioID: UUID(),
        channelIndex: 2,
        text: "Private channel message",
        directionRawValue: MessageDirection.outgoing.rawValue,
        statusRawValue: MessageStatus.sent.rawValue
    )
    return UnifiedMessageBubble(
        message: MessageDTO(from: message),
        contactName: "Private Group",
        deviceName: "My Device",
        configuration: .channel(isPublic: false, contacts: [])
    )
}

#Preview("Incoming - Direct Path") {
    let message = Message(
        radioID: UUID(),
        contactID: UUID(),
        text: "This came directly!",
        directionRawValue: MessageDirection.incoming.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue,
        pathLength: 0
    )
    return UnifiedMessageBubble(
        message: MessageDTO(from: message),
        contactName: "Alice",
        configuration: .directMessage
    )
}

#Preview("Incoming - 3 Hop Path") {
    let message = Message(
        radioID: UUID(),
        contactID: UUID(),
        text: "Routed through 3 nodes",
        directionRawValue: MessageDirection.incoming.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue,
        pathLength: 3
    )
    message.pathNodes = Data([0xA3, 0x7F, 0x42])
    return UnifiedMessageBubble(
        message: MessageDTO(from: message),
        contactName: "Bob",
        configuration: .directMessage
    )
}

#Preview("Incoming - 6 Hop Truncated") {
    let message = Message(
        radioID: UUID(),
        contactID: UUID(),
        text: "Long path message",
        directionRawValue: MessageDirection.incoming.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue,
        pathLength: 6
    )
    message.pathNodes = Data([0xA3, 0x7F, 0x42, 0xB2, 0xC1, 0xD4])
    return UnifiedMessageBubble(
        message: MessageDTO(from: message),
        contactName: "Charlie",
        configuration: .directMessage
    )
}
