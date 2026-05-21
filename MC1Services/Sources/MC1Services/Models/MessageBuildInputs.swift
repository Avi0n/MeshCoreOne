import Foundation

/// Per-message build inputs. `Sendable`, value-typed snapshot the builder
/// consumes. The view model constructs one per message at build time from
/// its current properties. `imageRefs` carry handles, not UIImages —
/// UIImage resolution happens at render time via the bubble's
/// `imageResolver` callback (UIImage is not Sendable). Env-derived flags
/// live on `EnvInputs`, the builder's second parcel.
public struct MessageBuildInputs: Sendable, Hashable {
    public let messageID: UUID
    public let previewState: PreviewLoadState
    public let loadedPreview: LinkPreviewDataDTO?
    public let cachedURL: URL?
    public let hasInlineImageRef: Bool
    public let hasPreviewImageRef: Bool
    public let hasPreviewIconRef: Bool
    public let imageIsGIF: Bool
    /// Cached width-over-height ratio for `cachedURL` when it points to an
    /// inline image. Resolved at build time so the bubble can reserve the
    /// correct frame on first paint. `nil` for non-image URLs or when the
    /// dimensions store has not yet seen this URL.
    public let inlineImageAspect: Double?
    public let formattedText: AttributedString?
    public let baseColor: BaseColorSlot
    public let formattedPath: String?
    public let senderResolution: NodeNameResolution
    public let showTimestamp: Bool
    public let showDirectionGap: Bool
    public let showSenderName: Bool
    public let showNewMessagesDivider: Bool

    public init(
        messageID: UUID,
        previewState: PreviewLoadState,
        loadedPreview: LinkPreviewDataDTO?,
        cachedURL: URL?,
        hasInlineImageRef: Bool,
        hasPreviewImageRef: Bool,
        hasPreviewIconRef: Bool,
        imageIsGIF: Bool,
        inlineImageAspect: Double? = nil,
        formattedText: AttributedString?,
        baseColor: BaseColorSlot,
        formattedPath: String?,
        senderResolution: NodeNameResolution,
        showTimestamp: Bool,
        showDirectionGap: Bool,
        showSenderName: Bool,
        showNewMessagesDivider: Bool
    ) {
        self.messageID = messageID
        self.previewState = previewState
        self.loadedPreview = loadedPreview
        self.cachedURL = cachedURL
        self.hasInlineImageRef = hasInlineImageRef
        self.hasPreviewImageRef = hasPreviewImageRef
        self.hasPreviewIconRef = hasPreviewIconRef
        self.imageIsGIF = imageIsGIF
        self.inlineImageAspect = inlineImageAspect
        self.formattedText = formattedText
        self.baseColor = baseColor
        self.formattedPath = formattedPath
        self.senderResolution = senderResolution
        self.showTimestamp = showTimestamp
        self.showDirectionGap = showDirectionGap
        self.showSenderName = showSenderName
        self.showNewMessagesDivider = showNewMessagesDivider
    }
}
