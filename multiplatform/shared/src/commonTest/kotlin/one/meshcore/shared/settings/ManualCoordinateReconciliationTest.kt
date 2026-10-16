package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two reconciliations a verified manual coordinate forces, mirroring the Swift
 * `LocationPickerView.forLocalDevice` post-write block.
 */
class ManualCoordinateReconciliationTest {
    @Test
    fun aDeviceGpsAutoUpdateIsTurnedOffBecauseTheWriteTurnedTheGpsOff() {
        val reconciliation = manualCoordinateReconciliation(
            autoUpdateLocation = true,
            gpsSource = GpsSource.DEVICE,
            advertLocationPolicyRaw = AdvertLocationPolicy.SHARE.rawValue,
        )

        assertTrue(reconciliation.disableAutoUpdate)
    }

    @Test
    fun aPhoneSourcedAutoUpdateSurvivesTheManualWrite() {
        val reconciliation = manualCoordinateReconciliation(
            autoUpdateLocation = true,
            gpsSource = GpsSource.PHONE,
            advertLocationPolicyRaw = AdvertLocationPolicy.PREFS.rawValue,
        )

        assertFalse(reconciliation.disableAutoUpdate)
    }

    /** A node told to advertise its live fix has no live fix left once the manual write stops the GPS. */
    @Test
    fun aShareLiveFixPolicyIsDemotedToTheStoredCoordinate() {
        val reconciliation = manualCoordinateReconciliation(
            autoUpdateLocation = false,
            gpsSource = GpsSource.PHONE,
            advertLocationPolicyRaw = AdvertLocationPolicy.SHARE.rawValue,
        )

        assertTrue(reconciliation.demoteAdvertPolicy)
    }

    @Test
    fun aStoredCoordinatePolicyIsLeftAlone() {
        assertFalse(
            manualCoordinateReconciliation(
                autoUpdateLocation = false,
                gpsSource = GpsSource.PHONE,
                advertLocationPolicyRaw = AdvertLocationPolicy.PREFS.rawValue,
            ).demoteAdvertPolicy,
        )
        assertFalse(
            manualCoordinateReconciliation(
                autoUpdateLocation = false,
                gpsSource = GpsSource.PHONE,
                advertLocationPolicyRaw = AdvertLocationPolicy.NONE.rawValue,
            ).demoteAdvertPolicy,
        )
    }
}
