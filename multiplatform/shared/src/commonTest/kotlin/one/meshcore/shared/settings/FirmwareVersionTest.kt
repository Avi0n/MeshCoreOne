package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirmwareVersionTest {
    @Test
    fun bareAndVPrefixedVersionsCompareOnMajorMinor() {
        assertTrue(FirmwareVersion.isAtLeast("v1.15.0", major = 1, minor = 15))
        assertTrue(FirmwareVersion.isAtLeast("1.15", major = 1, minor = 15))
        assertTrue(FirmwareVersion.isAtLeast("v1.15", major = 1, minor = 15))
        assertTrue(FirmwareVersion.isAtLeast("v1.15.0-abc", major = 1, minor = 15))
        assertFalse(FirmwareVersion.isAtLeast("v1.14.1", major = 1, minor = 15))
        assertFalse(FirmwareVersion.isAtLeast("1.14", major = 1, minor = 15))
    }

    @Test
    fun cliVerBannerComparesOnTheFirstMajorMinor() {
        assertTrue(FirmwareVersion.isAtLeast("MeshCore v1.15.0 (2025-04-18)", major = 1, minor = 15))
        assertTrue(FirmwareVersion.isAtLeast("MeshCore v1.15.0 (2025-04-18)", major = 1, minor = 14))
        assertFalse(FirmwareVersion.isAtLeast("MeshCore v1.14.1 (2025-04-18)", major = 1, minor = 15))
    }

    @Test
    fun unparseableStringsAreNotAtLeastAnyVersion() {
        assertFalse(FirmwareVersion.isAtLeast("MeshCore", major = 1, minor = 0))
        assertFalse(FirmwareVersion.isAtLeast("", major = 1, minor = 0))
        assertFalse(FirmwareVersion.isAtLeast("v1", major = 1, minor = 0))
    }

    @Test
    fun regionDefaultScopeRequiresV115() {
        assertFalse(FirmwareVersion.supportsRegionDefaultScope(null))
        assertFalse(FirmwareVersion.supportsRegionDefaultScope("v1.14.1"))
        assertTrue(FirmwareVersion.supportsRegionDefaultScope("v1.15.0"))
        assertTrue(FirmwareVersion.supportsRegionDefaultScope("MeshCore v1.15.0 (2025-04-18)"))
    }
}
