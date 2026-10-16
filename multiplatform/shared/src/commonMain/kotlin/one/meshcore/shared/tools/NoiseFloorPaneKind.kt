package one.meshcore.shared.tools

/** Which empty/content pane Noise Floor shows. A failed first poll must not keep looking like collecting. */
enum class NoiseFloorPaneKind {
    DISCONNECTED,
    COLLECTING,
    ERROR,
    CONTENT,
}

fun noiseFloorPaneKind(hasFetcher: Boolean, hasReading: Boolean, errorMessage: String?): NoiseFloorPaneKind = when {
    !hasFetcher -> NoiseFloorPaneKind.DISCONNECTED
    hasReading -> NoiseFloorPaneKind.CONTENT
    !errorMessage.isNullOrBlank() -> NoiseFloorPaneKind.ERROR
    else -> NoiseFloorPaneKind.COLLECTING
}

/**
 * Samples only while a radio session is ready. A disconnected screen keeps the Not Connected
 * pane and does not poll, matching the iOS gate on the live session.
 */
fun noiseFloorFetcher(
    isRadioReady: Boolean,
    fetchReading: (suspend () -> NoiseFloorReading?)?,
): (suspend () -> NoiseFloorReading?)? = if (isRadioReady) fetchReading else null
