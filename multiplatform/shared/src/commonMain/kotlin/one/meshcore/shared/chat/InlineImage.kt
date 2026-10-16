package one.meshcore.shared.chat

/**
 * Display state of an inline image inside a bubble. Value-typed and fully structural so a state change
 * (idle to loading to loaded) reloads the row through item equality. Mirrors the Swift `InlineImage`.
 *
 * [cachedAspect] is the width-over-height ratio resolved at build time; null means the dimensions store has
 * not seen this URL yet and the view falls back to its 16:9 reservation skeleton.
 */
data class InlineImage(val state: LoadState, val autoPlayGIFs: Boolean, val cachedAspect: Double? = null) {
    sealed interface LoadState {
        /**
         * Pre-gate: a URL was detected but the malware blocklist and SSRF checks have not cleared it yet.
         * Carries no URL, so no view can start a fetch from it — the safety checks are suspending, and a
         * fetching state rendered while they resolve would issue the request the checks exist to prevent.
         */
        data object Pending : LoadState

        /** Cleared by both safety gates; the view fetches this URL. */
        data class Loading(val url: String) : LoadState

        data class Loaded(val image: ImageReference, val isGIF: Boolean) : LoadState

        data class Failed(val url: String) : LoadState

        /**
         * Scope-off tap-to-load placeholder: the master toggle is on but auto-resolve is disabled for this
         * conversation type, so the image is not fetched until the user taps.
         */
        data class Disabled(val url: String) : LoadState
    }
}
