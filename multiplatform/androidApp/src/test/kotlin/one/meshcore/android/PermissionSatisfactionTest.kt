package one.meshcore.android

import android.Manifest
import android.os.Build
import one.meshcore.shared.ui.OnboardingPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onboarding card's grant rule. Location asks for fine and coarse together, and an
 * approximate-only outcome must read as granted; a card whose permissions are all genuinely required
 * still needs every one of them.
 */
class PermissionSatisfactionTest {
    @Test
    fun locationApproximateOnlyIsSatisfied() {
        assertTrue(isSatisfied(OnboardingPermission.LOCATION, listOf(false, true)))
    }

    @Test
    fun locationPreciseIsSatisfied() {
        assertTrue(isSatisfied(OnboardingPermission.LOCATION, listOf(true, true)))
    }

    @Test
    fun locationDeniedIsNotSatisfied() {
        assertFalse(isSatisfied(OnboardingPermission.LOCATION, listOf(false, false)))
    }

    @Test
    fun bluetoothNeedsEveryPermission() {
        assertTrue(isSatisfied(OnboardingPermission.BLUETOOTH, listOf(true, true)))
        assertFalse(isSatisfied(OnboardingPermission.BLUETOOTH, listOf(true, false)))
    }

    @Test
    fun bluetoothBelowApi31AsksForLocationBecauseBleScanNeedsIt() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            bluetoothManifestPermissions(Build.VERSION_CODES.Q),
        )
    }

    @Test
    fun bluetoothOnApi31UsesScanAndConnect() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            bluetoothManifestPermissions(Build.VERSION_CODES.S),
        )
    }
}
