package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grant-to-status rule behind `AndroidLocationService.readAuthorization`. Mirrors the iOS
 * `LocationService.isAuthorized` rule: full accuracy is not required, so an approximate-only grant is
 * an authorized state and not a denial.
 */
class AndroidLocationAuthorizationTest {
    @Test
    fun preciseGrantAuthorizes() {
        val status = locationAuthorizationStatus(fineGranted = true, coarseGranted = true)
        assertEquals(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE, status)
        assertTrue(status.isAuthorized)
    }

    @Test
    fun approximateOnlyGrantAuthorizes() {
        val status = locationAuthorizationStatus(fineGranted = false, coarseGranted = true)
        assertEquals(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE, status)
        assertTrue(status.isAuthorized)
    }

    @Test
    fun fineOnlyGrantAuthorizes() {
        val status = locationAuthorizationStatus(fineGranted = true, coarseGranted = false)
        assertEquals(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE, status)
        assertTrue(status.isAuthorized)
    }

    @Test
    fun noGrantIsNotDetermined() {
        val status = locationAuthorizationStatus(fineGranted = false, coarseGranted = false)
        assertEquals(LocationAuthorizationStatus.NOT_DETERMINED, status)
        assertFalse(status.isAuthorized)
        assertFalse(status.hasBeenRequested)
    }

    @Test
    fun anAskedAndRefusedGrantIsDenied() {
        val status = locationAuthorizationStatus(
            fineGranted = false,
            coarseGranted = false,
            hasAsked = true,
        )
        assertEquals(LocationAuthorizationStatus.DENIED, status)
        assertTrue(status.isDenied)
        assertFalse(status.isAuthorized)
    }
}
