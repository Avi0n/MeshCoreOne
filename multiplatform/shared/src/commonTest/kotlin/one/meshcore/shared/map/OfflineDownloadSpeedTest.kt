package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * iOS `OfflineMapService.loadPacks` snapshots consecutive completed-byte counts and only exposes
 * a speed while the pack is active. Storage Used is the offline db file length, not a pack sum.
 */
class OfflineDownloadSpeedTest {
    @Test
    fun firstSnapshotHasNoSpeed() {
        val next = nextOfflineDownloadSpeed(previous = null, currentBytes = 1_000, currentElapsedMs = 1_000)
        assertEquals(1_000L, next.bytes)
        assertNull(next.speedBytesPerSec)
    }

    @Test
    fun speedIsDeltaBytesOverElapsedSecondsAfterHalfASecond() {
        val first = nextOfflineDownloadSpeed(previous = null, currentBytes = 1_000, currentElapsedMs = 1_000)
        val next = nextOfflineDownloadSpeed(previous = first, currentBytes = 3_000, currentElapsedMs = 2_000)
        assertEquals(2_000L, next.speedBytesPerSec)
    }

    @Test
    fun unchangedBytesZeroTheSpeed() {
        val first = OfflineDownloadSpeedSnapshot(bytes = 4_000, elapsedMs = 1_000, speedBytesPerSec = 800)
        val next = nextOfflineDownloadSpeed(previous = first, currentBytes = 4_000, currentElapsedMs = 2_000)
        assertEquals(0L, next.speedBytesPerSec)
    }

    @Test
    fun displaySpeedIsHiddenWhenPausedCompleteOrZero() {
        assertEquals(1_200L, displayedOfflineDownloadSpeed(completed = false, paused = false, speedBytesPerSec = 1_200))
        assertNull(displayedOfflineDownloadSpeed(completed = true, paused = false, speedBytesPerSec = 1_200))
        assertNull(displayedOfflineDownloadSpeed(completed = false, paused = true, speedBytesPerSec = 1_200))
        assertNull(displayedOfflineDownloadSpeed(completed = false, paused = false, speedBytesPerSec = 0))
        assertNull(displayedOfflineDownloadSpeed(completed = false, paused = false, speedBytesPerSec = null))
    }

    @Test
    fun storageUsedIsTheDatabaseFileLengthNotAPackSum() {
        assertEquals(12_345L, offlineMapStorageUsedBytes(databaseFileLength = 12_345))
        assertEquals(0L, offlineMapStorageUsedBytes(databaseFileLength = null))
        assertEquals(0L, offlineMapStorageUsedBytes(databaseFileLength = -1))
    }

    @Test
    fun packHeadlineJoinsRegionAndLayerWithAComma() {
        assertEquals("Bay Area, Base", offlinePackHeadline("Bay Area", "Base"))
    }
}
