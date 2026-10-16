package one.meshcore.shared.chat

/**
 * Loading state of a message's single detected URL. Precomputed by the view model and handed to the pure
 * [MessageFragmentBuilder]; it selects which inline-image / link-preview state the fragment carries and
 * whether a fetch should be requested. Mirrors the Swift `PreviewLoadState`.
 */
enum class PreviewLoadState {
    IDLE,
    LOADING,
    LOADED,
    NO_PREVIEW,
    DISABLED,
    MALWARE_WARNING,
}
