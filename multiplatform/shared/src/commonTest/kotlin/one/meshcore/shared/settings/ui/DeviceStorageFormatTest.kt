package one.meshcore.shared.settings.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Storage labels for Device Info, matching iOS `DeviceInfoView.formatStorage` (binary kilobytes).
 */
class DeviceStorageFormatTest {
    @Test
    fun formatStorageUsesBinaryKilobytes() {
        assertEquals("0 bytes / 2 MB", formatStorage(usedKB = 0, totalKB = 2048))
        assertEquals("512 KB / 2 MB", formatStorage(usedKB = 512, totalKB = 2048))
        assertEquals("1.2 MB / 4 MB", formatStorage(usedKB = 1234, totalKB = 4096))
    }

    @Test
    fun formatMemoryBytesScalesUnits() {
        assertEquals("512 bytes", formatMemoryBytes(512))
        assertEquals("1 KB", formatMemoryBytes(1024))
        assertEquals("1 MB", formatMemoryBytes(1024L * 1024L))
    }
}
