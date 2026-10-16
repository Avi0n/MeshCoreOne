package one.meshcore.shared.chat

/**
 * Environment-derived inputs that influence item content across the whole timeline (as opposed to the
 * per-message [MessageBuildInputs]). A change here forces a full rebuild. Mirrors the Swift `EnvInputs`:
 * the local user's name and the incoming-send-time toggle drive the text/footer build, and the preview,
 * map / appearance toggles gate the richer fragments.
 *
 * Rich-content fields default off, so a caller that supplies only the required values still produces
 * text-only items.
 */
data class EnvInputs(
    val currentUserName: String,
    val showIncomingSendTime: Boolean,
    /** Auto-play GIFs; carried onto every inline-image fragment. */
    val autoPlayGIFs: Boolean = true,
    /** Master toggle gating both the inline-image and link-preview fragments. */
    val previewsEnabled: Boolean = false,
    /** Privacy gate: when false the builder skips the map-preview fragment so no tile request ever fires. */
    val showMapPreviews: Boolean = false,
    /** Light/dark appearance of the app chrome. */
    val isDark: Boolean = false,
    /** Independent basemap appearance, part of the map snapshot request key. */
    val mapBasemapIsDark: Boolean = isDark,
    /** True when the network is unavailable; part of the map snapshot request key. */
    val isOffline: Boolean = false,
    /** Footer toggle: show the hop count on a flood-routed incoming message. */
    val showIncomingHopCount: Boolean = false,
    /** Footer toggle: show the region scope on a flood-routed incoming message. */
    val showIncomingRegion: Boolean = false,
    /** Footer toggle: show the total heard arrivals on an incoming message with extra paths. */
    val showIncomingHeardCount: Boolean = false,
    /** Localized labels that enable the routing path row; null keeps the row hidden. */
    val incomingPathLabels: MessagePathLabels? = null,
) {
    companion object {
        val DEFAULT = EnvInputs(currentUserName = "", showIncomingSendTime = false)
    }
}
