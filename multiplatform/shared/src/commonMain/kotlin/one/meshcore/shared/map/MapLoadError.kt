package one.meshcore.shared.map

import one.meshcore.shared.contact.ContactServiceError

/**
 * Why the map could not load its located nodes. Mirrors the iOS `MapViewModel.loadMapData` catch,
 * which assigns `error.userFacingMessage` to `errorMessage`; the typed cases keep the English out of
 * the model so the UI layer resolves the copy.
 */
sealed interface MapLoadError {
    /** No live radio, so there is nothing to read contacts from. */
    data object NotConnected : MapLoadError

    /** A lower-layer failure carrying already-localized user-facing copy. */
    data class ServiceMessage(val message: String) : MapLoadError
}

/** Classifies a contact-load failure for the map's error surface. */
fun mapContactLoadError(error: Throwable, errorText: (Throwable) -> String): MapLoadError = when (error) {
    is ContactServiceError.NotConnected -> MapLoadError.NotConnected
    else -> MapLoadError.ServiceMessage(errorText(error))
}
