package one.meshcore.shared.map

import one.meshcore.shared.contact.ContactServiceError
import kotlin.test.Test
import kotlin.test.assertEquals

/** A failed contact load is classified for the map's error surface instead of vanishing. */
class MapLoadErrorTest {
    @Test
    fun notConnectedGetsItsOwnCase() {
        assertEquals(
            MapLoadError.NotConnected,
            mapContactLoadError(ContactServiceError.NotConnected(), errorText = { "Localized error" }),
        )
    }

    @Test
    fun otherFailuresCarryMappedCopyNotExceptionText() {
        assertEquals(
            MapLoadError.ServiceMessage("mapped"),
            mapContactLoadError(ContactServiceError.InvalidResponse(), errorText = { "mapped" }),
        )
    }

    @Test
    fun aMessagelessFailureStillClassifies() {
        assertEquals(
            MapLoadError.ServiceMessage("Localized error"),
            mapContactLoadError(RuntimeException(), errorText = { "Localized error" }),
        )
    }
}
