package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkAvailabilityTest {
    @Test
    fun aMissingRouteIsOffline() {
        assertTrue(isNetworkOfflineFromInternetCapability(null))
    }

    @Test
    fun aRouteWithoutInternetIsOffline() {
        assertTrue(isNetworkOfflineFromInternetCapability(false))
    }

    @Test
    fun aRouteWithInternetIsOnline() {
        assertFalse(isNetworkOfflineFromInternetCapability(true))
    }
}
