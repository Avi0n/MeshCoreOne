package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineMapStorageGateTest {
    @Test
    fun exceedsAvailableSpaceWhenEstimateLarger() {
        val room = OfflineMapStorageGate.MINIMUM_OFFLINE_MAP_FREE_BYTES + 1_000
        assertTrue(OfflineMapStorageGate.exceedsAvailableSpace(estimatedBytes = room + 1, availableBytes = room))
        assertFalse(OfflineMapStorageGate.exceedsAvailableSpace(estimatedBytes = room, availableBytes = room))
        assertFalse(OfflineMapStorageGate.exceedsAvailableSpace(estimatedBytes = room - 1, availableBytes = room))
    }

    @Test
    fun exceedsAvailableSpaceWhenBelowTheHundredMegabyteFloor() {
        val below = OfflineMapStorageGate.MINIMUM_OFFLINE_MAP_FREE_BYTES - 1
        assertTrue(OfflineMapStorageGate.exceedsAvailableSpace(estimatedBytes = 1L, availableBytes = below))
        assertTrue(OfflineMapStorageGate.exceedsAvailableSpace(estimatedBytes = null, availableBytes = below))
    }

    @Test
    fun exceedsAvailableSpaceNeverTrueWhenCapacityUnknown() {
        assertFalse(OfflineMapStorageGate.exceedsAvailableSpace(estimatedBytes = 10L, availableBytes = null))
        assertFalse(OfflineMapStorageGate.exceedsAvailableSpace(estimatedBytes = null, availableBytes = null))
    }

    @Test
    fun canDownloadNeedsNameSelectionSpaceAndNetwork() {
        assertTrue(
            OfflineMapStorageGate.canDownload(
                hasRegionName = true,
                hasSelection = true,
                exceedsAvailableSpace = false,
                isNetworkAvailable = true,
            ),
        )
        assertFalse(
            OfflineMapStorageGate.canDownload(
                hasRegionName = false,
                hasSelection = true,
                exceedsAvailableSpace = false,
                isNetworkAvailable = true,
            ),
        )
        assertFalse(
            OfflineMapStorageGate.canDownload(
                hasRegionName = true,
                hasSelection = false,
                exceedsAvailableSpace = false,
                isNetworkAvailable = true,
            ),
        )
        assertFalse(
            OfflineMapStorageGate.canDownload(
                hasRegionName = true,
                hasSelection = true,
                exceedsAvailableSpace = true,
                isNetworkAvailable = true,
            ),
        )
    }

    @Test
    fun canDownloadBlockedWhenOffline() {
        assertFalse(
            OfflineMapStorageGate.canDownload(
                hasRegionName = true,
                hasSelection = true,
                exceedsAvailableSpace = false,
                isNetworkAvailable = false,
            ),
        )
    }

    @Test
    fun totalStorageBytesSumsPackSizes() {
        val packs = listOf(
            OfflineMapPack("a", "A", OfflineMapLayer.BASE, 1, sizeBytes = 1_000),
            OfflineMapPack("b", "B", OfflineMapLayer.TOPO, 2, sizeBytes = 2_500),
            OfflineMapPack("c", "C", OfflineMapLayer.BASE, 3, sizeBytes = -5),
        )
        assertEquals(3_500L, OfflineMapStorageGate.totalStorageBytes(packs))
        assertEquals(0L, OfflineMapStorageGate.totalStorageBytes(emptyList()))
    }
}
