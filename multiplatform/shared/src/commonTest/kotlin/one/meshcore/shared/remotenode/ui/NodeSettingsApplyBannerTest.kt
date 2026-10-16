package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Success apply outcomes share the inline form caption with failures. Material reports the
 * outcome in place instead of a Success + OK dialog.
 */
class NodeSettingsApplyBannerTest {
    @Test
    fun successOutcomesTintAsSuccessOnTheFormCaption() {
        val success = listOf(
            ApplyBanner.Success,
            ApplyBanner.RadioApplied,
            ApplyBanner.AdvertSent,
            ApplyBanner.TimeSynced,
            ApplyBanner.RegionsSaved,
            ApplyBanner.PasswordChanged,
            ApplyBanner.RebootSent,
        )
        success.forEach { banner ->
            assertTrue(isSuccessBanner(banner), "$banner reads as a failure")
        }
    }

    @Test
    fun failuresStayInlineAsErrors() {
        val failures = listOf(
            ApplyBanner.PartialFailure,
            ApplyBanner.ClockAhead,
            ApplyBanner.SyncTimeFailed,
            ApplyBanner.NoService,
            ApplyBanner.TransportError("lost"),
        )
        failures.forEach { banner ->
            assertFalse(isSuccessBanner(banner), "$banner reads as a success")
        }
    }

    @Test
    fun addChildUsesTheMaterialAddListIcon() {
        assertEquals(AppIcons.Add, REGION_ADD_CHILD_ICON)
    }
}
