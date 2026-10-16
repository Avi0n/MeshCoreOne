package one.meshcore.shared.chat

/**
 * Display state for a chat map-location thumbnail. [isDark] and [isOffline] are carried from the build so
 * the view's snapshot request key matches the one the build used to compute [isReady]; the view must not
 * re-read the environment. [isReady] flips true once the snapshot for this coordinate's
 * `(rounded lat/lon, isDark, isOffline)` request resolves (cached or failed), which changes this value and
 * reloads the row. Mirrors the Swift `MapPreviewFragmentState`.
 */
data class MapPreviewFragmentState(
    val latitude: Double,
    val longitude: Double,
    val isDark: Boolean,
    val isOffline: Boolean,
    val isReady: Boolean,
)
