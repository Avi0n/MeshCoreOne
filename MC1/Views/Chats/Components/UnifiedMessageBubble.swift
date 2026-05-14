import SwiftUI
import MC1Services

/// Unified message bubble for both direct and channel messages
struct UnifiedMessageBubble: View {
    let message: MessageDTO
    let contactName: String
    let deviceName: String
    let configuration: MessageBubbleConfiguration
    let item: MessageItem
    let imageResolver: (ImageReference) -> UIImage?
    let callbacks: MessageBubbleCallbacks

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    @State private var showingReactionDetails = false
    @State private var longPressTriggered = false

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

            if item.grouping.showTimestamp {
                MessageTimestampView(date: item.envelope.date)
            }

            HStack(alignment: .bottom, spacing: 4) {
                if item.envelope.isOutgoing {
                    Spacer(minLength: 40)
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
                        callbacks: callbacks,
                        imageResolver: imageResolver
                    )
                    .onLongPressGesture(minimumDuration: 0.3) {
                        longPressTriggered.toggle()
                        callbacks.onLongPress?()
                    }
                    .sensoryFeedback(.impact(weight: .medium), trigger: longPressTriggered)

                    ForEach(Array(item.content.enumerated()), id: \.offset) { _, fragment in
                        siblingFragmentView(fragment)
                    }

                    if item.footer.showStatusRow {
                        BubbleStatusRow(message: message, onRetry: callbacks.onRetry)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(accessibilityMessageLabel)

                if !item.envelope.isOutgoing {
                    Spacer(minLength: 40)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, paddingTop(item: item))
        .padding(.bottom, 0)
        .onAppear {
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
            MalwareWarningFragmentView(url: url)
        case .linkPreview(let state):
            LinkPreviewFragmentView(
                state: state,
                imageResolver: imageResolver,
                onManualPreviewFetch: callbacks.onManualPreviewFetch
            )
        case .text, .inlineImage:
            EmptyView()
        }
    }

    // MARK: - Computed Properties

    private var senderColor: Color {
        AppColors.NameColor.color(
            for: item.envelope.senderName,
            highContrast: colorSchemeContrast == .increased
        )
    }

    private func paddingTop(item: MessageItem) -> CGFloat {
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
            label += ", \(BubbleStatusRow.statusText(for: message))"
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

// MARK: - Extracted Views

/// The colored, clipped bubble box: text, optional footer, and optional inline
/// image. Reactions, malware warnings, and link previews are emitted as
/// siblings by `UnifiedMessageBubble.body` so they sit below the bubble box.
private struct BubbleFragmentStack: View {
    let item: MessageItem
    let callbacks: MessageBubbleCallbacks
    let imageResolver: (ImageReference) -> UIImage?

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var bubbleColor: Color {
        if item.envelope.isOutgoing {
            return item.envelope.hasFailed
                ? AppColors.Message.outgoingBubbleFailed
                : AppColors.Message.outgoingBubble
        } else {
            return AppColors.Message.incomingBubble
        }
    }

    private var hasFooter: Bool {
        item.footer.showHop
            || item.footer.formattedPath != nil
            || item.footer.regionToShow != nil
    }

    private var inlineImageFragment: InlineImage? {
        for fragment in item.content {
            if case .inlineImage(let inlineImage) = fragment {
                return inlineImage
            }
        }
        return nil
    }

    private var textPayload: MessageTextPayload? {
        for fragment in item.content {
            if case .text(let payload) = fragment {
                return payload
            }
        }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                if let textPayload {
                    MessageTextView(text: textPayload)
                }

                if !item.envelope.isOutgoing && hasFooter {
                    BubbleFooterRow(footer: item.footer, dynamicTypeSize: dynamicTypeSize)
                }
            }
            .bubbleContentPadding()

            if let inlineImage = inlineImageFragment {
                InlineImageFragmentView(
                    inlineImage: inlineImage,
                    isOutgoing: item.envelope.isOutgoing,
                    imageResolver: imageResolver,
                    onTap: { callbacks.onImageTap?() },
                    onRetry: { callbacks.onRetryImageFetch?() }
                )
            }
        }
        .background(bubbleColor)
        .clipShape(.rect(cornerRadius: 16))
    }
}

/// Renders the hop / path / region trio from a `MessageFooter`. HStack at
/// standard dynamic type sizes; VStack when `dynamicTypeSize.isAccessibilitySize`
/// is true. Each sub-row carries its own `.accessibilityLabel(...)`; the
/// container uses `.accessibilityElement(children: .combine)` so VoiceOver
/// surfaces the trio as a single rotor stop.
private struct BubbleFooterRow: View {
    let footer: MessageFooter
    let dynamicTypeSize: DynamicTypeSize

    var body: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 2) {
                footerContents(allowsWrap: true)
            }
            .accessibilityElement(children: .combine)
        } else {
            HStack(spacing: 4) {
                footerContents(allowsWrap: false)
            }
            .accessibilityElement(children: .combine)
        }
    }

    @ViewBuilder
    private func footerContents(allowsWrap: Bool) -> some View {
        if footer.showHop {
            BubbleHopCountFooter(hopCount: footer.hopCount)
        }
        if let formattedPath = footer.formattedPath {
            BubblePathFooter(formattedPath: formattedPath)
        }
        if let region = footer.regionToShow {
            BubbleRegionFooter(regionName: region, allowsWrap: allowsWrap)
        }
    }
}

private struct BubbleStatusRow: View {
    let message: MessageDTO
    let onRetry: (() -> Void)?

    private static let minimumTapTargetHeight: CGFloat = 44

    var body: some View {
        HStack(spacing: 4) {
            if message.status == .failed, let onRetry {
                Button {
                    onRetry()
                } label: {
                    HStack(spacing: 2) {
                        Image(systemName: "arrow.clockwise")
                        Text(L10n.Chats.Chats.Message.Status.retry)
                    }
                    .font(.caption2)
                    .frame(minHeight: Self.minimumTapTargetHeight)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.blue)
            }

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
        .font(.caption2)
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

extension View {
    func bubbleContentPadding() -> some View {
        padding(.horizontal, 10)
            .padding(.vertical, 8)
    }
}
