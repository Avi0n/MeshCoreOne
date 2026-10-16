package one.meshcore.shared.map

import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import one.meshcore.shared.settings.RadioPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RegionalAreas catalog lookups the region resolver and preset recommendation depend on, ported
 * from the iOS `RegionalAreas` behavior: long-form and short-code subdivision matching, US-only county
 * matching, and the display-name composition rules.
 */
class RegionalAreasTest {
    @Test
    fun showsSubdivisionPickerIsTrueForUsAndAuFalseForPtAndNil() {
        assertTrue(RegionalAreas.showsSubdivisionPicker("US"))
        assertTrue(RegionalAreas.showsSubdivisionPicker("AU"))
        assertFalse(RegionalAreas.showsSubdivisionPicker("PT"))
        assertFalse(RegionalAreas.showsSubdivisionPicker("CA"))
        assertFalse(RegionalAreas.showsSubdivisionPicker(null))
    }

    @Test
    fun matchSubdivisionResolvesLongFormAndShortCode() {
        assertEquals("US-CA", RegionalAreas.matchSubdivision(country = "US", normalized = "california"))
        assertEquals("US-CA", RegionalAreas.matchSubdivision(country = "US", normalized = "ca"))
        assertEquals("AU-QLD", RegionalAreas.matchSubdivision(country = "AU", normalized = "queensland"))
        assertEquals("AU-QLD", RegionalAreas.matchSubdivision(country = "AU", normalized = "qld"))
        assertEquals("AU-SA", RegionalAreas.matchSubdivision(country = "AU", normalized = "south australia"))
        assertEquals("AU-WA", RegionalAreas.matchSubdivision(country = "AU", normalized = "wa"))
        assertEquals("US-PA", RegionalAreas.matchSubdivision(country = "US", normalized = "pennsylvania"))
        assertEquals("US-PA", RegionalAreas.matchSubdivision(country = "US", normalized = "pa"))
        assertEquals("US-NJ", RegionalAreas.matchSubdivision(country = "US", normalized = "new jersey"))
        assertEquals("US-DE", RegionalAreas.matchSubdivision(country = "US", normalized = "delaware"))
        assertEquals("US-MD", RegionalAreas.matchSubdivision(country = "US", normalized = "maryland"))
    }

    @Test
    fun matchSubdivisionMissesUnknownNamesAndCountries() {
        assertNull(RegionalAreas.matchSubdivision(country = "US", normalized = "pr"))
        assertNull(RegionalAreas.matchSubdivision(country = "DE", normalized = "bavaria"))
        assertNull(RegionalAreas.matchSubdivision(country = "US", normalized = null))
        assertNull(RegionalAreas.matchSubdivision(country = "XX", normalized = "california"))
    }

    @Test
    fun usSubdivisionsListsAllFiftyStatesAndDc() {
        val ids = RegionalAreas.usSubdivisions.map { it.id }.toSet()
        assertEquals(51, ids.size)
        assertTrue("US-CA" in ids)
        assertTrue("US-TX" in ids)
        assertTrue("US-DC" in ids)
        assertTrue("US-HI" in ids)
    }

    @Test
    fun auSubdivisionsListsAllStatesAndTerritories() {
        assertEquals(
            setOf(
                "AU-ACT",
                "AU-NSW",
                "AU-NT",
                "AU-QLD",
                "AU-SA",
                "AU-TAS",
                "AU-VIC",
                "AU-WA",
            ),
            RegionalAreas.auSubdivisions.map { it.id }.toSet(),
        )
    }

    @Test
    fun administrativeAreaKindIsStateExceptCanada() {
        assertEquals(RegionalAreas.AdministrativeAreaKind.STATE, RegionalAreas.administrativeAreaKind("US"))
        assertEquals(RegionalAreas.AdministrativeAreaKind.STATE, RegionalAreas.administrativeAreaKind("AU"))
        assertEquals(RegionalAreas.AdministrativeAreaKind.PROVINCE, RegionalAreas.administrativeAreaKind("CA"))
        assertEquals(RegionalAreas.AdministrativeAreaKind.STATE, RegionalAreas.administrativeAreaKind("PT"))
    }

    @Test
    fun matchSubdivisionFindsTexasAndVictoriaAndDcExtras() {
        assertEquals("US-TX", RegionalAreas.matchSubdivision(country = "US", normalized = "texas"))
        assertEquals("US-TX", RegionalAreas.matchSubdivision(country = "US", normalized = "tx"))
        assertEquals("AU-VIC", RegionalAreas.matchSubdivision(country = "AU", normalized = "victoria"))
        assertEquals("AU-NSW", RegionalAreas.matchSubdivision(country = "AU", normalized = "nsw"))
        assertEquals("US-DC", RegionalAreas.matchSubdivision(country = "US", normalized = "washington dc"))
        assertEquals("US-DC", RegionalAreas.matchSubdivision(country = "US", normalized = "washington d.c."))
    }

    @Test
    fun displayNameShowsTexasAlone() {
        val selection = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-TX",
            source = RegionSource.MANUAL,
        )
        assertEquals("Texas", RegionalAreas.displayName(selection, inLocale = EN))
    }

    @Test
    fun countryCatalogMatchesRadioPresetContinents() {
        assertEquals(
            RadioPresets.continents.keys,
            RegionalAreas.countries.map { it.id }.toSet(),
        )
    }

    @Test
    fun matchCountyResolvesCatalogedCaliforniaCounties() {
        assertEquals(
            "los angeles",
            RegionalAreas.matchCounty(country = "US", state = "US-CA", normalized = "los angeles"),
        )
        assertEquals(
            "san luis obispo",
            RegionalAreas.matchCounty(country = "US", state = "US-CA", normalized = "san luis obispo"),
        )
    }

    @Test
    fun matchCountyIsUsOnlyAndMissesUnknownInputs() {
        assertNull(RegionalAreas.matchCounty(country = "AU", state = "AU-QLD", normalized = "los angeles"))
        assertNull(RegionalAreas.matchCounty(country = "US", state = "US-TX", normalized = "los angeles"))
        assertNull(RegionalAreas.matchCounty(country = "US", state = "US-CA", normalized = "sacramento"))
        assertNull(RegionalAreas.matchCounty(country = "US", state = null, normalized = "los angeles"))
        assertNull(RegionalAreas.matchCounty(country = "US", state = "US-CA", normalized = null))
    }

    @Test
    fun displayNameShowsStateAloneForUsAndCanada() {
        val selection = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            source = RegionSource.LOCATION,
        )
        assertEquals("California", RegionalAreas.displayName(selection, inLocale = EN))
    }

    @Test
    fun displayNameCombinesStateAndCountryElsewhere() {
        val selection = RegionSelection(
            countryCode = "AU",
            administrativeAreaCode = "AU-QLD",
            source = RegionSource.MANUAL,
        )
        assertEquals("Queensland, Australia", RegionalAreas.displayName(selection, inLocale = EN))
    }

    @Test
    fun displayNameFallsBackToCountryThenRawCodes() {
        val countryOnly = RegionSelection(countryCode = "NZ", source = RegionSource.MANUAL)
        assertEquals("New Zealand", RegionalAreas.displayName(countryOnly, inLocale = EN))

        val unknown = RegionSelection(
            countryCode = "XX",
            administrativeAreaCode = "XX-YY",
            source = RegionSource.MANUAL,
        )
        assertEquals("XX-YY, XX", RegionalAreas.displayName(unknown, inLocale = EN))
    }

    @Test
    fun countryDisplayNameFollowsTheRequestedLocale() {
        assertEquals("Germany", RegionalAreas.countryDisplayName("DE", EN))
        assertEquals("Deutschland", RegionalAreas.countryDisplayName("DE", "de"))
    }

    @Test
    fun countriesSortedByLocalizedNameOrdersEnglishNames() {
        val ids = RegionalAreas.countriesSortedByLocalizedName(EN).map { it.id }
        assertEquals("AU", ids.first())
        assertEquals("VN", ids.last())
    }

    @Test
    fun displayNamePrefersLocalizedSubdivisionOverEnglishFallback() {
        val selection = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            source = RegionSource.LOCATION,
        )
        assertEquals(
            "Kalifornien",
            RegionalAreas.displayName(selection, inLocale = "de", subdivisionNames = mapOf("US-CA" to "Kalifornien")),
        )
    }

    private companion object {
        const val EN = "en"
    }
}
