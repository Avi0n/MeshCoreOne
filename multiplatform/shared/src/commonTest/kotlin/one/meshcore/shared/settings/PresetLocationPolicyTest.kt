package one.meshcore.shared.settings

import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import one.meshcore.shared.map.LocationAuthorizationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresetLocationPolicyTest {
    private fun usCA(): RegionSelection =
        RegionSelection(countryCode = "US", administrativeAreaCode = "US-CA", source = RegionSource.LOCATION)

    private fun makePortugal(source: RegionSource): RegionSelection =
        RegionSelection(countryCode = "PT", source = source)

    @Test
    fun expandOnlyWhenNotAuthorizedAndIncomplete() {
        assertTrue(PresetLocationPolicy.shouldExpandOnRadio(authorized = false, selection = null))
        assertFalse(PresetLocationPolicy.shouldExpandOnRadio(authorized = true, selection = null))
        val usNoAdmin = RegionSelection(countryCode = "US", source = RegionSource.MANUAL)
        assertTrue(PresetLocationPolicy.shouldExpandOnRadio(authorized = false, selection = usNoAdmin))
        assertFalse(PresetLocationPolicy.shouldExpandOnRadio(authorized = true, selection = usNoAdmin))
        assertFalse(PresetLocationPolicy.shouldExpandOnRadio(authorized = false, selection = usCA()))
    }

    @Test
    fun incompleteIsNilOrASubdividedCountryWithNoAdministrativeAreaCode() {
        assertTrue(PresetLocationPolicy.isIncomplete(null))
        assertTrue(
            PresetLocationPolicy.isIncomplete(RegionSelection(countryCode = "US", source = RegionSource.MANUAL)),
        )
        assertFalse(PresetLocationPolicy.isIncomplete(usCA()))
        assertFalse(
            PresetLocationPolicy.isIncomplete(
                RegionSelection(countryCode = "US", administrativeAreaCode = "US-TX", source = RegionSource.MANUAL),
            ),
        )
        assertTrue(
            PresetLocationPolicy.isIncomplete(RegionSelection(countryCode = "AU", source = RegionSource.MANUAL)),
        )
        assertFalse(PresetLocationPolicy.isIncomplete(makePortugal(source = RegionSource.MANUAL)))
    }

    @Test
    fun shouldResolveOnAppearIsTrueOnlyWhenAuthorizedAndSourceIsNotManual() {
        assertTrue(PresetLocationPolicy.shouldResolveOnAppear(authorized = true, source = null))
        assertTrue(PresetLocationPolicy.shouldResolveOnAppear(authorized = true, source = RegionSource.LOCATION))
        assertFalse(PresetLocationPolicy.shouldResolveOnAppear(authorized = true, source = RegionSource.MANUAL))
        assertFalse(PresetLocationPolicy.shouldResolveOnAppear(authorized = false, source = RegionSource.LOCATION))
        assertFalse(PresetLocationPolicy.shouldResolveOnAppear(authorized = false, source = null))
    }

    @Test
    fun useMyLocationActionMapsAuthorizationStatus() {
        assertEquals(
            PresetLocationPolicy.UseMyLocationAction.RESOLVE,
            PresetLocationPolicy.useMyLocationAction(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE),
        )
        assertEquals(
            PresetLocationPolicy.UseMyLocationAction.RESOLVE,
            PresetLocationPolicy.useMyLocationAction(LocationAuthorizationStatus.AUTHORIZED_ALWAYS),
        )
        assertEquals(
            PresetLocationPolicy.UseMyLocationAction.WAIT_FOR_AUTHORIZATION,
            PresetLocationPolicy.useMyLocationAction(LocationAuthorizationStatus.NOT_DETERMINED),
        )
        assertEquals(
            PresetLocationPolicy.UseMyLocationAction.OPEN_SETTINGS,
            PresetLocationPolicy.useMyLocationAction(LocationAuthorizationStatus.DENIED),
        )
        assertEquals(
            PresetLocationPolicy.UseMyLocationAction.OPEN_SETTINGS,
            PresetLocationPolicy.useMyLocationAction(LocationAuthorizationStatus.RESTRICTED),
        )
    }

    @Test
    fun appearCommitKeepsManualPortugalOverCaliforniaGps() {
        val portugal = makePortugal(source = RegionSource.MANUAL)
        val california = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            source = RegionSource.LOCATION,
        )
        assertEquals(
            portugal,
            PresetLocationPolicy.committedSelection(
                current = portugal,
                result = california,
                kind = PresetLocationPolicy.ResolveKind.APPEAR,
            ),
        )
    }

    @Test
    fun userInitiatedCommitReplacesManualPortugalWithCaliforniaGps() {
        val portugal = makePortugal(source = RegionSource.MANUAL)
        val california = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            source = RegionSource.LOCATION,
        )
        assertEquals(
            california,
            PresetLocationPolicy.committedSelection(
                current = portugal,
                result = california,
                kind = PresetLocationPolicy.ResolveKind.USER_INITIATED,
            ),
        )
    }

    @Test
    fun actionAfterAuthorizationWait() {
        assertEquals(
            PresetLocationPolicy.AfterAuthorizationWait.RESOLVE,
            PresetLocationPolicy.actionAfterAuthorizationWait(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE),
        )
        assertEquals(
            PresetLocationPolicy.AfterAuthorizationWait.OPEN_SETTINGS,
            PresetLocationPolicy.actionAfterAuthorizationWait(LocationAuthorizationStatus.DENIED),
        )
        assertEquals(
            PresetLocationPolicy.AfterAuthorizationWait.OPEN_SETTINGS,
            PresetLocationPolicy.actionAfterAuthorizationWait(LocationAuthorizationStatus.RESTRICTED),
        )
        assertEquals(
            PresetLocationPolicy.AfterAuthorizationWait.NONE,
            PresetLocationPolicy.actionAfterAuthorizationWait(LocationAuthorizationStatus.NOT_DETERMINED),
        )
    }

    @Test
    fun lookupMissIsSilentOnAppearAndWhenRequestIsInFlight() {
        assertFalse(
            PresetLocationPolicy.shouldPresentLookupMiss(
                kind = PresetLocationPolicy.ResolveKind.APPEAR,
                requestInProgress = false,
            ),
        )
        assertFalse(
            PresetLocationPolicy.shouldPresentLookupMiss(
                kind = PresetLocationPolicy.ResolveKind.USER_INITIATED,
                requestInProgress = true,
            ),
        )
        assertTrue(
            PresetLocationPolicy.shouldPresentLookupMiss(
                kind = PresetLocationPolicy.ResolveKind.USER_INITIATED,
                requestInProgress = false,
            ),
        )
    }

    @Test
    fun committedSelectionWithNilGpsKeepsCurrent() {
        val portugal = makePortugal(source = RegionSource.MANUAL)
        assertEquals(
            portugal,
            PresetLocationPolicy.committedSelection(
                current = portugal,
                result = null,
                kind = PresetLocationPolicy.ResolveKind.APPEAR,
            ),
        )
        assertEquals(
            portugal,
            PresetLocationPolicy.committedSelection(
                current = portugal,
                result = null,
                kind = PresetLocationPolicy.ResolveKind.USER_INITIATED,
            ),
        )
        assertEquals(
            null,
            PresetLocationPolicy.committedSelection(
                current = null,
                result = null,
                kind = PresetLocationPolicy.ResolveKind.APPEAR,
            ),
        )
    }

    @Test
    fun shouldCommitAppearResultIsFalseOnlyForManual() {
        assertFalse(PresetLocationPolicy.shouldCommitAppearResult(currentSource = RegionSource.MANUAL))
        assertTrue(PresetLocationPolicy.shouldCommitAppearResult(currentSource = RegionSource.LOCATION))
        assertTrue(PresetLocationPolicy.shouldCommitAppearResult(currentSource = null))
    }

    @Test
    fun appearCommitOfNilCurrentTakesGpsResult() {
        val california = usCA()
        assertEquals(
            california,
            PresetLocationPolicy.committedSelection(
                current = null,
                result = california,
                kind = PresetLocationPolicy.ResolveKind.APPEAR,
            ),
        )
    }

    @Test
    fun appearCommitOfLocationCurrentTakesGpsResult() {
        val portugal = makePortugal(source = RegionSource.LOCATION)
        val california = usCA()
        assertEquals(
            california,
            PresetLocationPolicy.committedSelection(
                current = portugal,
                result = california,
                kind = PresetLocationPolicy.ResolveKind.APPEAR,
            ),
        )
    }
}
