package one.meshcore.shared.chat

/**
 * Display state of a link-preview card inside a bubble. Value-typed and structural so a mode change reloads
 * the row through item equality. Mirrors the Swift `LinkPreviewFragmentState`.
 */
data class LinkPreviewFragmentState(val mode: Mode) {
    sealed interface Mode {
        data object Idle : Mode

        data class Loading(val url: String) : Mode

        data class Loaded(val preview: LinkPreviewDataDto, val image: ImageReference?, val icon: ImageReference?) :
            Mode

        data object NoPreview : Mode

        data class Disabled(val url: String) : Mode

        data class Legacy(val url: String, val title: String?, val image: ImageReference?, val icon: ImageReference?) :
            Mode
    }

    /**
     * The single openable URL the preview resolves to: the destination of a loaded or legacy card, null for
     * modes that show no openable card. Read by both the card tap and the accessibility "open link" action
     * so the URL is derived once.
     */
    val primaryURL: String?
        get() = when (val mode = mode) {
            is Mode.Loaded -> mode.preview.url
            is Mode.Legacy -> mode.url
            Mode.Idle, is Mode.Loading, Mode.NoPreview, is Mode.Disabled -> null
        }
}
