package one.meshcore.shared.chat.linkpreview

/** In-memory link-preview settings seeded with shipping defaults for tests. */
class InMemoryLinkPreviewSettings(
    override var previewsEnabled: Boolean = LinkPreviewSettings.DEFAULT_PREVIEWS_ENABLED,
    override var autoResolveDM: Boolean = LinkPreviewSettings.DEFAULT_AUTO_RESOLVE_DM,
    override var autoResolveChannels: Boolean = LinkPreviewSettings.DEFAULT_AUTO_RESOLVE_CHANNELS,
) : LinkPreviewSettings
