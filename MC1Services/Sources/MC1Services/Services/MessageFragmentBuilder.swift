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
            return fragments
        }

        if isImageURL && envInputs.showInlineImages {
            fragments.append(.inlineImage(
                makeInlineImage(url: url, inputs: inputs, envInputs: envInputs)
            ))
        } else if envInputs.previewsEnabled {
            fragments.append(.linkPreview(
                makeLinkPreview(message: message, url: url, inputs: inputs)
            ))
        }

        return fragments
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
        let loadState: InlineImage.LoadState
        switch inputs.previewState {
        case .loaded:
            if inputs.hasInlineImageRef {
                loadState = .loaded(
                    ImageReference(cacheKey: inputs.messageID, role: .inline),
                    isGIF: inputs.imageIsGIF
                )
            } else if let url {
                loadState = .loading(url)
            } else {
                loadState = .failed(Self.blankURL)
            }
        case .loading:
            loadState = url.map { .loading($0) } ?? .failed(Self.blankURL)
        case .noPreview:
            loadState = url.map { .failed($0) } ?? .failed(Self.blankURL)
        case .idle, .disabled, .malwareWarning:
            loadState = url.map { .idle($0) } ?? .failed(Self.blankURL)
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

        let mode: LinkPreviewFragmentState.Mode
        switch inputs.previewState {
        case .loaded:
            if let preview = inputs.loadedPreview {
                mode = .loaded(preview, image: imageRef, icon: iconRef)
            } else {
                mode = .noPreview
            }
        case .loading:
            mode = url.map { .loading($0) } ?? .idle
        case .noPreview:
            mode = .noPreview
        case .disabled:
            mode = url.map { .disabled($0) } ?? .idle
        case .idle:
            if let urlString = message.linkPreviewURL,
               let legacyURL = URL(string: urlString) {
                mode = .legacy(
                    url: legacyURL,
                    title: message.linkPreviewTitle,
                    image: imageRef,
                    icon: iconRef
                )
            } else if let url {
                mode = .loading(url)
            } else {
                mode = .idle
            }
        case .malwareWarning:
            mode = .idle
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
            date: message.date,
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
        let region: String?
        if envInputs.showIncomingRegion, message.isFloodRouted {
            region = message.regionScope
        } else {
            region = nil
        }
        return MessageFooter(
            showHop: showHop,
            hopCount: message.hopCount,
            formattedPath: inputs.formattedPath,
            regionToShow: region,
            showStatusRow: message.isOutgoing,
            status: message.status,
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
            showNewMessagesDivider: inputs.showNewMessagesDivider
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
