package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvertLocationPolicyTest {
    @Test
    fun fromSharePubliclyMapsIosSemantics() {
        assertEquals(AdvertLocationPolicy.PREFS, AdvertLocationPolicy.fromSharePublicly(true))
        assertEquals(AdvertLocationPolicy.NONE, AdvertLocationPolicy.fromSharePublicly(false))
    }

    @Test
    fun isEnabledForShareAndPrefs() {
        assertFalse(AdvertLocationPolicy.NONE.isEnabled)
        assertTrue(AdvertLocationPolicy.SHARE.isEnabled)
        assertTrue(AdvertLocationPolicy.PREFS.isEnabled)
    }

    @Test
    fun sharesTheLiveFixOnlyWhenTheNodesOwnGpsFeedsIt() {
        assertEquals(
            AdvertLocationPolicy.SHARE,
            AdvertLocationPolicy.forSharePublicly(
                share = true,
                autoUpdateLocation = true,
                deviceHasGps = true,
                gpsSource = GpsSource.DEVICE,
            ),
        )
    }

    @Test
    fun sharesTheStoredCoordinateForEveryOtherConfiguration() {
        assertEquals(
            AdvertLocationPolicy.PREFS,
            AdvertLocationPolicy.forSharePublicly(
                share = true,
                autoUpdateLocation = true,
                deviceHasGps = true,
                gpsSource = GpsSource.PHONE,
            ),
        )
        assertEquals(
            AdvertLocationPolicy.PREFS,
            AdvertLocationPolicy.forSharePublicly(
                share = true,
                autoUpdateLocation = false,
                deviceHasGps = true,
                gpsSource = GpsSource.DEVICE,
            ),
        )
        assertEquals(
            AdvertLocationPolicy.PREFS,
            AdvertLocationPolicy.forSharePublicly(
                share = true,
                autoUpdateLocation = true,
                deviceHasGps = false,
                gpsSource = GpsSource.DEVICE,
            ),
        )
    }

    @Test
    fun sharingOffAlwaysWins() {
        assertEquals(
            AdvertLocationPolicy.NONE,
            AdvertLocationPolicy.forSharePublicly(
                share = false,
                autoUpdateLocation = true,
                deviceHasGps = true,
                gpsSource = GpsSource.DEVICE,
            ),
        )
    }

    @Test
    fun snapshotSharePubliclyUsesPolicy() {
        val off = LocationSettingsSnapshot(0.0, 0.0, AdvertLocationPolicy.NONE.rawValue)
        assertFalse(off.shareLocationPublicly)
        assertFalse(off.hasLocation)
        val on = LocationSettingsSnapshot(37.5, -122.1, AdvertLocationPolicy.PREFS.rawValue)
        assertTrue(on.shareLocationPublicly)
        assertTrue(on.hasLocation)
    }

    /**
     * A policy byte this app does not model still carries a position: node-config import writes an
     * unmodeled byte through verbatim, and Swift `Device.sharesLocationPublicly` reads any non-zero
     * policy as sharing. Reading it as "not sharing" hides the toggle state and skips the pre-advert
     * GPS refresh on a node that is in fact broadcasting its position.
     */
    @Test
    fun anUnmodeledPolicyByteStillCountsAsSharing() {
        val unmodeled = LocationSettingsSnapshot(37.5, -122.1, UNMODELED_POLICY_RAW)
        assertTrue(unmodeled.shareLocationPublicly)
    }

    private companion object {
        /** Past [AdvertLocationPolicy.PREFS]; the range a newer firmware or a foreign config can use. */
        const val UNMODELED_POLICY_RAW = 3
    }
}
