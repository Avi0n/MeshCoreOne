package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapStyleGateTest {
    @Test
    fun onlineEnablesEveryStyle() {
        for (style in MapStyleSelection.entries) {
            assertFalse(
                MapStyleGate.isDisabled(style, isNetworkAvailable = true, hasOfflineCoverage = false),
                "online should never disable $style",
            )
            assertNull(MapStyleGate.disabledReason(style, isNetworkAvailable = true, hasOfflineCoverage = false))
        }
    }

    @Test
    fun offlineDisablesNetworkOnlyStyle() {
        assertTrue(
            MapStyleGate.isDisabled(MapStyleSelection.SATELLITE, isNetworkAvailable = false, hasOfflineCoverage = true),
        )
        assertEquals(
            MapStyleDisabledReason.REQUIRES_NETWORK,
            MapStyleGate.disabledReason(
                MapStyleSelection.SATELLITE,
                isNetworkAvailable = false,
                hasOfflineCoverage = true,
            ),
        )
    }

    @Test
    fun offlineDisablesStyleWithoutCoverage() {
        assertTrue(
            MapStyleGate.isDisabled(MapStyleSelection.TOPO, isNetworkAvailable = false, hasOfflineCoverage = false),
        )
        assertEquals(
            MapStyleDisabledReason.NO_OFFLINE_COVERAGE,
            MapStyleGate.disabledReason(MapStyleSelection.TOPO, isNetworkAvailable = false, hasOfflineCoverage = false),
        )
    }

    @Test
    fun offlineKeepsCoveredStyleEnabled() {
        assertFalse(
            MapStyleGate.isDisabled(MapStyleSelection.STANDARD, isNetworkAvailable = false, hasOfflineCoverage = true),
        )
        assertNull(
            MapStyleGate.disabledReason(
                MapStyleSelection.STANDARD,
                isNetworkAvailable = false,
                hasOfflineCoverage = true,
            ),
        )
    }
}
