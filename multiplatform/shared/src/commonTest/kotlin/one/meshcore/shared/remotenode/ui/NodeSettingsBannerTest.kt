package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.remotenode.NodeSettingsResponseParser
import one.meshcore.shared.remotenode.PasswordApplyResult
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.SettingsApplyResult
import one.meshcore.shared.remotenode.SyncTimeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Both remote-settings screens map an outcome to a banner through these, so one node reply cannot be
 * reported two ways. Mirrors the Swift `NodeSettingsViewModel` sync and radio-apply branches.
 */
class NodeSettingsBannerTest {
    /** The firmware's own wording is more specific than any sentence we ship, so it survives to the user. */
    @Test
    fun aDescribedFailureKeepsTheNodesOwnWords() {
        val banner = SyncTimeResult.Failed("clock not set").toBanner()

        assertEquals(ApplyBanner.FirmwareError("clock not set"), banner)
    }

    /** With nothing to quote there is no point showing an empty banner. */
    @Test
    fun anUndescribedFailureFallsBackToTheShippedSentence() {
        assertEquals(ApplyBanner.SyncTimeFailed, SyncTimeResult.Failed("").toBanner())
        assertEquals(ApplyBanner.SyncTimeFailed, SyncTimeResult.Failed("   ").toBanner())
    }

    /** An unclassifiable reply is only useful if the reply itself is shown. */
    @Test
    fun anUnexpectedReplyIsQuoted() {
        val banner = SyncTimeResult.Unexpected("WAT: 17").toBanner()

        assertEquals(ApplyBanner.UnexpectedResponse("WAT: 17"), banner)
    }

    @Test
    fun theParserCarriesTheReplyItCouldNotClassify() {
        val outcome = NodeSettingsResponseParser.classifyClockSyncResponse("WAT: 17")

        assertTrue(outcome is NodeSettingsResponseParser.ClockSyncOutcome.Unexpected)
        assertEquals("WAT: 17", outcome.response)
    }

    @Test
    fun everySyncOutcomeGetsItsOwnBanner() {
        val banners = listOf(
            SyncTimeResult.Synced,
            SyncTimeResult.ClockAhead,
            SyncTimeResult.Failed("a"),
            SyncTimeResult.Unexpected("b"),
            SyncTimeResult.TransportError(RemoteNodeError.Timeout),
        ).map { it.toBanner() }

        assertEquals(banners.size, banners.toSet().size)
    }

    /**
     * New radio parameters only take effect after a restart, and a refusal names the radio: the general
     * "some settings failed" would not say which setting on a radio-only apply.
     */
    @Test
    fun aRadioApplyNamesTheRadioInBothOutcomes() {
        assertEquals(ApplyBanner.RadioApplied, SettingsApplyResult.Applied.toRadioBanner())
        assertEquals(ApplyBanner.RadioApplyFailed, SettingsApplyResult.PartialFailure.toRadioBanner())
    }

    /**
     * The tint is decided separately from the mapping, so a success routed through a banner the tint
     * does not know paints the sentence in the error colour and reads as a failed apply.
     */
    @Test
    fun everySuccessOutcomeIsTintedAsOne() {
        val successes = listOfNotNull(
            SettingsApplyResult.Applied.toBanner(),
            SettingsApplyResult.Applied.toRadioBanner(),
            SyncTimeResult.Synced.toBanner(),
            ApplyBanner.RebootSent,
            ApplyBanner.AdvertSent,
            ApplyBanner.RegionsSaved,
            ApplyBanner.PasswordChanged,
        )

        successes.forEach { assertTrue(isSuccessBanner(it), "$it reads as a failure") }
    }

    @Test
    fun aRefusalIsNotTintedAsASuccess() {
        val failures = listOfNotNull(
            SettingsApplyResult.PartialFailure.toRadioBanner(),
            SettingsApplyResult.PartialFailure.toBanner(),
            SyncTimeResult.Failed("clock not set").toBanner(),
            ApplyBanner.NoService,
        )

        failures.forEach { assertFalse(isSuccessBanner(it), "$it reads as a success") }
    }

    @Test
    fun theOtherRadioOutcomesKeepTheirGeneralMeaning() {
        assertNull(SettingsApplyResult.InvalidFields.toRadioBanner())
        assertEquals(ApplyBanner.NotLoaded, SettingsApplyResult.NotLoaded.toRadioBanner())
        assertEquals(
            ApplyBanner.TransportError("Request timed out"),
            SettingsApplyResult.TransportError(RemoteNodeError.Timeout).toRadioBanner(),
        )
    }

    @Test
    fun anAppliedSectionFlashesInsteadOfASuccessBanner() {
        assertEquals(ApplyBanner.Success, SettingsApplyResult.Applied.toBanner())
        assertNull(SettingsApplyResult.Applied.bannerUnlessApplied())
        assertEquals(ApplyBanner.PartialFailure, SettingsApplyResult.PartialFailure.bannerUnlessApplied())
        assertNull(SettingsApplyResult.InvalidFields.bannerUnlessApplied())
        assertEquals(ApplyBanner.NotLoaded, SettingsApplyResult.NotLoaded.bannerUnlessApplied())
    }

    @Test
    fun aLocalValidationRefusalHasNoBanner() {
        assertNull(SettingsApplyResult.InvalidFields.toBanner())
        assertNull(SettingsApplyResult.InvalidFields.toRadioBanner())
    }

    @Test
    fun aTransportFailureKeepsTheErrorDescription() {
        assertEquals(
            ApplyBanner.TransportError("Permission denied"),
            SettingsApplyResult.TransportError(RemoteNodeError.PermissionDenied).toBanner(),
        )
        assertEquals(
            ApplyBanner.TransportError("Radio contact list is full"),
            SettingsApplyResult.TransportError(RemoteNodeError.RadioContactsFull).toBanner(),
        )
    }

    @Test
    fun aPasswordRefusalNamesTheRefusal() {
        assertNull(PasswordApplyResult.Applied.toBanner())
        assertEquals(ApplyBanner.PasswordEmpty, PasswordApplyResult.Empty.toBanner())
        assertEquals(ApplyBanner.PasswordMismatch, PasswordApplyResult.Mismatch.toBanner())
        assertEquals(ApplyBanner.PasswordFailed, PasswordApplyResult.Failed.toBanner())
        assertEquals(
            ApplyBanner.TransportError("Request timed out"),
            PasswordApplyResult.TransportError(RemoteNodeError.Timeout).toBanner(),
        )
    }
}
