package one.meshcore.shared.settings

import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioPresetsTest {
    @Test
    fun matchingPresetMatchesUsCanada() {
        val us = RadioPresets.all.first { it.id == "us-ca" }
        val matches = RadioPresets.matchingPresets(
            frequencyKHz = us.frequencyKHz,
            bandwidthHz = us.bandwidthHz,
            spreadingFactor = us.spreadingFactor,
            codingRate = us.codingRate,
        )
        assertEquals(listOf("us-ca", "ca"), matches.map { it.id })
        assertNull(
            RadioPresets.matchingPreset(
                frequencyKHz = us.frequencyKHz,
                bandwidthHz = us.bandwidthHz,
                spreadingFactor = us.spreadingFactor,
                codingRate = us.codingRate,
            ),
        )
        assertEquals(
            "us-ca",
            RadioPresets.resolvedPreset(
                rf = us.presetRf(),
                region = RegionSelection(countryCode = "US", source = RegionSource.MANUAL),
            )?.id,
        )
        assertEquals(
            "ca",
            RadioPresets.resolvedPreset(
                rf = us.presetRf(),
                region = RegionSelection(countryCode = "CA", source = RegionSource.MANUAL),
            )?.id,
        )
    }

    @Test
    fun matchingPresetRejectsSfMismatch() {
        val us = RadioPresets.all.first { it.id == "us-ca" }
        val match = RadioPresets.matchingPreset(
            frequencyKHz = us.frequencyKHz,
            bandwidthHz = us.bandwidthHz,
            spreadingFactor = us.spreadingFactor + 1,
            codingRate = us.codingRate,
        )
        assertNull(match)
    }

    @Test
    fun recommendedUsIsUsCa() {
        val region = RegionSelection(countryCode = "US", source = RegionSource.MANUAL)
        assertEquals("us-ca", RadioPresets.recommended(region)?.id)
    }

    @Test
    fun recommendedSoCalCountyIsWcmesh() {
        val region = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            countyKey = "los angeles",
            source = RegionSource.LOCATION,
        )
        assertEquals("wcmesh", RadioPresets.recommended(region)?.id)
    }

    @Test
    fun recommendedPennsylvaniaAndNewJerseyAreLvMesh() {
        for (admin in listOf("US-PA", "US-NJ")) {
            val region = RegionSelection(
                countryCode = "US",
                administrativeAreaCode = admin,
                source = RegionSource.LOCATION,
            )
            assertEquals("lvmesh", RadioPresets.recommended(region)?.id, admin)
        }
    }

    @Test
    fun recommendedDelawareAndMarylandArePhillyMesh() {
        for (admin in listOf("US-DE", "US-MD")) {
            val region = RegionSelection(
                countryCode = "US",
                administrativeAreaCode = admin,
                source = RegionSource.LOCATION,
            )
            assertEquals("phillymesh", RadioPresets.recommended(region)?.id, admin)
        }
    }

    @Test
    fun phillyMeshPresetCarriesCommunityRadioParameters() {
        val preset = RadioPresets.all.first { it.id == "phillymesh" }
        assertEquals("PhillyMesh", preset.name)
        assertEquals(919.500, preset.frequencyMHz)
        assertEquals(500.0, preset.bandwidthKHz)
        assertEquals(10, preset.spreadingFactor)
        assertEquals(5, preset.codingRate)
        assertEquals(2, preset.pathHashSize)
        assertEquals(1, preset.pathHashMode)
        assertEquals(RadioRegion.NORTH_AMERICA, preset.region)
        assertEquals(
            listOf("phillymesh"),
            RadioPresets.matchingPresets(
                frequencyKHz = preset.frequencyKHz,
                bandwidthHz = preset.bandwidthHz,
                spreadingFactor = preset.spreadingFactor,
                codingRate = preset.codingRate,
            ).map { it.id },
        )
    }

    @Test
    fun lvMeshPresetCarriesCommunityRadioParameters() {
        val preset = RadioPresets.all.first { it.id == "lvmesh" }
        assertEquals("LVMesh", preset.name)
        assertEquals(910.525, preset.frequencyMHz)
        assertEquals(500.0, preset.bandwidthKHz)
        assertEquals(10, preset.spreadingFactor)
        assertEquals(5, preset.codingRate)
        assertEquals(2, preset.pathHashSize)
        assertEquals(1, preset.pathHashMode)
        assertEquals(RadioRegion.NORTH_AMERICA, preset.region)
    }

    @Test
    fun recommendedEuropeIsEuNarrowFavorite() {
        val region = RegionSelection(countryCode = "DE", source = RegionSource.MANUAL)
        assertEquals("eu-narrow", RadioPresets.recommended(region)?.id)
    }

    @Test
    fun recommendedBrazilIsBr() {
        val region = RegionSelection(countryCode = "BR", source = RegionSource.LOCATION)
        assertEquals("br", RadioPresets.recommended(region)?.id)
    }

    @Test
    fun brazilPresetCarriesCommunityRadioParameters() {
        val preset = RadioPresets.all.first { it.id == "br" }
        assertEquals(923.125, preset.frequencyMHz)
        assertEquals(62.5, preset.bandwidthKHz)
        assertEquals(8, preset.spreadingFactor)
        assertEquals(8, preset.codingRate)
        assertEquals(RadioRegion.SOUTH_AMERICA, preset.region)
    }

    @Test
    fun presetsForBrazilIncludesBr() {
        val region = RegionSelection(countryCode = "BR", source = RegionSource.LOCATION)
        val ids = RadioPresets.presets(forRegion = region).map { it.id }
        assertTrue(ids.contains("br"))
    }

    @Test
    fun regionsForBrazilLeadWithSouthAmerica() {
        assertEquals(
            listOf(
                RadioRegion.SOUTH_AMERICA,
                RadioRegion.NORTH_AMERICA,
                RadioRegion.EUROPE,
                RadioRegion.OCEANIA,
                RadioRegion.ASIA,
            ),
            RadioRegion.regionsForCountry("BR"),
        )
    }

    @Test
    fun isSelectableGatesCountyPresets() {
        val wcmesh = RadioPresets.all.first { it.id == "wcmesh" }
        assertFalse(RadioPresets.isSelectable(wcmesh, null))
        assertFalse(
            RadioPresets.isSelectable(
                wcmesh,
                RegionSelection(countryCode = "US", source = RegionSource.MANUAL),
            ),
        )
        assertTrue(
            RadioPresets.isSelectable(
                wcmesh,
                RegionSelection(
                    countryCode = "US",
                    administrativeAreaCode = "US-CA",
                    countyKey = "orange",
                    source = RegionSource.LOCATION,
                ),
            ),
        )
        val usCa = RadioPresets.all.first { it.id == "us-ca" }
        assertTrue(RadioPresets.isSelectable(usCa, null))
        val phillymesh = RadioPresets.all.first { it.id == "phillymesh" }
        val lvmesh = RadioPresets.all.first { it.id == "lvmesh" }
        assertFalse(RadioPresets.isSelectable(phillymesh, null))
        assertFalse(RadioPresets.isSelectable(lvmesh, null))
        assertFalse(
            RadioPresets.isSelectable(
                phillymesh,
                RegionSelection(countryCode = "US", source = RegionSource.MANUAL),
            ),
        )
        assertFalse(
            RadioPresets.isSelectable(
                lvmesh,
                RegionSelection(countryCode = "US", source = RegionSource.MANUAL),
            ),
        )
    }

    @Test
    fun phillyAndLvmeshAreSelectableInPennsylvaniaAndNewJersey() {
        val phillymesh = RadioPresets.all.first { it.id == "phillymesh" }
        val lvmesh = RadioPresets.all.first { it.id == "lvmesh" }
        assertTrue(
            RadioPresets.isSelectable(
                phillymesh,
                RegionSelection(
                    countryCode = "US",
                    administrativeAreaCode = "US-PA",
                    source = RegionSource.LOCATION,
                ),
            ),
        )
        assertTrue(
            RadioPresets.isSelectable(
                lvmesh,
                RegionSelection(
                    countryCode = "US",
                    administrativeAreaCode = "US-PA",
                    source = RegionSource.LOCATION,
                ),
            ),
        )
        assertTrue(
            RadioPresets.isSelectable(
                phillymesh,
                RegionSelection(
                    countryCode = "US",
                    administrativeAreaCode = "US-NJ",
                    source = RegionSource.LOCATION,
                ),
            ),
        )
        assertTrue(
            RadioPresets.isSelectable(
                lvmesh,
                RegionSelection(
                    countryCode = "US",
                    administrativeAreaCode = "US-NJ",
                    source = RegionSource.LOCATION,
                ),
            ),
        )
    }

    @Test
    fun delawareAndMarylandSelectPhillymeshButNotLvmesh() {
        val phillymesh = RadioPresets.all.first { it.id == "phillymesh" }
        val lvmesh = RadioPresets.all.first { it.id == "lvmesh" }
        assertTrue(
            RadioPresets.isSelectable(
                phillymesh,
                RegionSelection(
                    countryCode = "US",
                    administrativeAreaCode = "US-DE",
                    source = RegionSource.LOCATION,
                ),
            ),
        )
        assertFalse(
            RadioPresets.isSelectable(
                lvmesh,
                RegionSelection(
                    countryCode = "US",
                    administrativeAreaCode = "US-DE",
                    source = RegionSource.LOCATION,
                ),
            ),
        )
        assertTrue(
            RadioPresets.isSelectable(
                phillymesh,
                RegionSelection(
                    countryCode = "US",
                    administrativeAreaCode = "US-MD",
                    source = RegionSource.LOCATION,
                ),
            ),
        )
        assertFalse(
            RadioPresets.isSelectable(
                lvmesh,
                RegionSelection(
                    countryCode = "US",
                    administrativeAreaCode = "US-MD",
                    source = RegionSource.LOCATION,
                ),
            ),
        )
    }

    @Test
    fun losAngelesAndQueenslandHideTheseCountyPresets() {
        val phillymesh = RadioPresets.all.first { it.id == "phillymesh" }
        val lvmesh = RadioPresets.all.first { it.id == "lvmesh" }
        assertFalse(
            RadioPresets.isSelectable(
                phillymesh,
                RegionSelection(
                    countryCode = "US",
                    administrativeAreaCode = "US-CA",
                    countyKey = "los angeles",
                    source = RegionSource.LOCATION,
                ),
            ),
        )
        assertFalse(
            RadioPresets.isSelectable(
                lvmesh,
                RegionSelection(
                    countryCode = "US",
                    administrativeAreaCode = "US-CA",
                    countyKey = "los angeles",
                    source = RegionSource.LOCATION,
                ),
            ),
        )
        val qld = RadioPresets.all.first { it.id == "au-qld" }
        assertFalse(RadioPresets.isSelectable(qld, null))
    }

    @Test
    fun visiblePresetsForCaliforniaExcludeEuNarrow() {
        val region = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            source = RegionSource.LOCATION,
        )
        val visible = RadioPresets.visiblePresets(region, activePresetId = null).map { it.id }
        assertTrue(visible.contains("us-ca"))
        assertFalse(visible.contains("eu-narrow"))
    }

    @Test
    fun visiblePresetsAppendATravelerActiveIdOutsideTheRegionalList() {
        val region = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            source = RegionSource.LOCATION,
        )
        val visible = RadioPresets.visiblePresets(region, activePresetId = "eu-narrow").map { it.id }
        assertTrue(visible.contains("us-ca"))
        assertTrue(visible.contains("eu-narrow"))
        assertFalse(RadioPresets.presets(region).any { it.id == "eu-narrow" })
    }

    @Test
    fun visiblePresetsShowWcmeshOnlyWhenTheCountyMatches() {
        val la = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            countyKey = "los angeles",
            source = RegionSource.LOCATION,
        )
        val laIds = RadioPresets.visiblePresets(la, activePresetId = null).map { it.id }
        assertTrue(laIds.contains("wcmesh"))
        assertTrue(laIds.contains("us-ca"))

        val california = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            source = RegionSource.MANUAL,
        )
        val californiaIds = RadioPresets.visiblePresets(california, activePresetId = null).map { it.id }
        assertFalse(californiaIds.contains("wcmesh"))
        assertTrue(californiaIds.contains("us-ca"))
    }

    @Test
    fun visiblePresetsWithoutAPlaceUseTheLocaleList() {
        val visible = RadioPresets.visiblePresets(region = null, activePresetId = null).map { it.id }
        assertFalse(visible.contains("wcmesh"))
        assertFalse(visible.contains("phillymesh"))
        assertFalse(visible.contains("lvmesh"))
        assertFalse(visible.contains("au-qld"))
        assertTrue(visible.contains("us-ca"))
        assertTrue(visible.contains("eu-narrow"))
    }

    @Test
    fun visiblePresetsForAnEmptyRegionalListFallBackToTheLocaleList() {
        val bermuda = RegionSelection(countryCode = "BM", source = RegionSource.MANUAL)
        assertEquals(
            RadioPresets.visiblePresets(region = null, activePresetId = null).map { it.id }.toSet(),
            RadioPresets.visiblePresets(bermuda, activePresetId = null).map { it.id }.toSet(),
        )
        assertTrue(RadioPresets.visiblePresets(bermuda, activePresetId = null).isNotEmpty())
    }

    @Test
    fun selectablePresetsKeepsTheLiveCountyPreset() {
        val wcmesh = RadioPresets.all.first { it.id == "wcmesh" }
        val noRegion = RadioPresets.selectablePresets(region = null, activePresetId = null)
        assertFalse(noRegion.any { it.id == "wcmesh" })
        val live = RadioPresets.selectablePresets(region = null, activePresetId = wcmesh.id)
        assertTrue(live.any { it.id == "wcmesh" })
        val orange = RadioPresets.selectablePresets(
            region = RegionSelection(
                countryCode = "US",
                administrativeAreaCode = "US-CA",
                countyKey = "orange",
                source = RegionSource.LOCATION,
            ),
            activePresetId = null,
        )
        assertTrue(orange.any { it.id == "wcmesh" })
    }

    @Test
    fun nearestRepeatPresetSnapsOffBand() {
        val nearest = RadioPresets.nearestRepeatPreset(910_000)
        assertNotNull(nearest)
        assertEquals("repeat-918", nearest.id)
    }

    @Test
    fun matchingDisplayedPresetUsesRepeatCatalogWhenRepeating() {
        val repeat = RadioPresets.repeatPresets.first { it.id == "repeat-918" }
        val snapshot = RadioDeviceSnapshot(
            frequencyKHz = repeat.frequencyKHz,
            bandwidthHz = 62_500,
            spreadingFactor = 7,
            codingRate = 5,
            clientRepeat = true,
        )
        assertEquals("repeat-918", RadioPresets.matchingDisplayedPreset(snapshot)?.id)
    }

    @Test
    fun matchingDisplayedPresetUsesRegionCatalogWhenNotRepeating() {
        val us = RadioPresets.all.first { it.id == "us-ca" }
        val snapshot = RadioDeviceSnapshot(
            frequencyKHz = us.frequencyKHz,
            bandwidthHz = us.bandwidthHz,
            spreadingFactor = us.spreadingFactor,
            codingRate = us.codingRate,
            clientRepeat = false,
        )
        assertNull(RadioPresets.matchingDisplayedPreset(snapshot)?.id)
        assertEquals(
            "us-ca",
            RadioPresets.matchingDisplayedPreset(
                snapshot,
                region = RegionSelection(countryCode = "US", source = RegionSource.MANUAL),
            )?.id,
        )
        assertEquals(
            "ca",
            RadioPresets.matchingDisplayedPreset(
                snapshot,
                region = RegionSelection(countryCode = "CA", source = RegionSource.MANUAL),
            )?.id,
        )
        assertEquals(
            "us-ca",
            RadioPresets.matchingDisplayedPreset(
                snapshot.copy(appliedRadioPresetID = "us-ca"),
                region = RegionSelection(countryCode = "CA", source = RegionSource.MANUAL),
            )?.id,
        )
    }

    @Test
    fun brazilRfMatchesAuSaWaAndBrInCatalogOrder() {
        val br = RadioPresets.all.first { it.id == "br" }
        assertEquals(
            listOf("au-sa-wa", "br"),
            RadioPresets.matchingPresets(
                frequencyKHz = br.frequencyKHz,
                bandwidthHz = br.bandwidthHz,
                spreadingFactor = br.spreadingFactor,
                codingRate = br.codingRate,
            ).map { it.id },
        )
    }

    @Test
    fun euNarrowRfMatchesEuNarrowAndChInCatalogOrder() {
        val ch = RadioPresets.all.first { it.id == "ch" }
        assertEquals(
            listOf("eu-narrow", "ch", "nl-li"),
            RadioPresets.matchingPresets(
                frequencyKHz = ch.frequencyKHz,
                bandwidthHz = ch.bandwidthHz,
                spreadingFactor = ch.spreadingFactor,
                codingRate = ch.codingRate,
            ).map { it.id },
        )
    }

    @Test
    fun preferredIdWinsAmongAliases() {
        val br = RadioPresets.all.first { it.id == "br" }
        assertEquals(
            "br",
            RadioPresets.resolvedPreset(rf = br.presetRf(), preferredID = "br")?.id,
        )
        assertEquals(
            "au-sa-wa",
            RadioPresets.resolvedPreset(rf = br.presetRf(), preferredID = "au-sa-wa")?.id,
        )
    }

    @Test
    fun stalePreferredIdDoesNotWinOnACollision() {
        val br = RadioPresets.all.first { it.id == "br" }
        assertNull(
            RadioPresets.resolvedPreset(rf = br.presetRf(), preferredID = "us-ca"),
        )
    }

    @Test
    fun preferredIdBeatsRecommended() {
        val br = RadioPresets.all.first { it.id == "br" }
        val auSA = RegionSelection(
            countryCode = "AU",
            administrativeAreaCode = "AU-SA",
            source = RegionSource.LOCATION,
        )
        assertEquals(
            "br",
            RadioPresets.resolvedPreset(rf = br.presetRf(), preferredID = "br", region = auSA)?.id,
        )
    }

    @Test
    fun retainedAppliedPresetIdDropsWhenRfNoLongerMatches() {
        val br = RadioPresets.all.first { it.id == "br" }
        val us = RadioPresets.all.first { it.id == "us-ca" }
        assertEquals(
            "br",
            RadioPresets.retainedAppliedPresetID(rf = br.presetRf(), preferredID = "br", clientRepeat = false),
        )
        assertNull(
            RadioPresets.retainedAppliedPresetID(rf = us.presetRf(), preferredID = "br", clientRepeat = false),
        )
        assertEquals(
            "br",
            RadioPresets.retainedAppliedPresetID(rf = us.presetRf(), preferredID = "br", clientRepeat = true),
        )
    }

    @Test
    fun setRadioArgsUseWireUnits() {
        val us = RadioPresets.all.first { it.id == "us-ca" }
        val args = RadioPresets.setRadioArgs(us)
        assertEquals(910.525, args.frequencyMHz)
        assertEquals(62.5, args.bandwidthKHz)
        assertEquals(7, args.spreadingFactor)
        assertEquals(5, args.codingRate)
    }

    @Test
    fun presetsForLocaleOrdersUsFirstForUs() {
        val ordered = RadioPresets.presetsForLocale("US")
        assertEquals(RadioRegion.NORTH_AMERICA, ordered.first().region)
    }
}

/**
 * Regional recommendations, path-hash firmware mapping, and the firmware gate for default flood scope.
 */
class RadioPresetsRecommendationTest {
    /**
     * The default-flood-scope opcode landed in firmware v11; iOS hides the section below that, so a
     * pre-v11 radio is never offered a write it will reject.
     */
    @Test
    fun defaultFloodScopeNeedsFirmwareEleven() {
        assertFalse(radioSnapshot(firmwareVersion = 10).supportsDefaultFloodScope)
        assertTrue(radioSnapshot(firmwareVersion = 11).supportsDefaultFloodScope)
    }

    @Test
    fun recommendedCanadaIsCa() {
        val region = RegionSelection(countryCode = "CA", source = RegionSource.MANUAL)
        assertEquals("ca", RadioPresets.recommended(region)?.id)
    }

    @Test
    fun recommendedHungaryIsHu() {
        val region = RegionSelection(countryCode = "HU", source = RegionSource.MANUAL)
        assertEquals("hu", RadioPresets.recommended(region)?.id)
    }

    @Test
    fun recommendedSlovakiaIsSk() {
        val region = RegionSelection(countryCode = "SK", source = RegionSource.MANUAL)
        assertEquals("sk", RadioPresets.recommended(region)?.id)
    }

    @Test
    fun recommendedCostaRicaIsCr() {
        val region = RegionSelection(countryCode = "CR", source = RegionSource.MANUAL)
        assertEquals("cr", RadioPresets.recommended(region)?.id)
    }

    @Test
    fun pathHashSizeMapsToFirmwareMode() {
        assertNull(RadioPresets.all.first { it.id == "us-ca" }.pathHashSize)
        assertNull(RadioPresets.all.first { it.id == "us-ca" }.pathHashMode)
        val hu = RadioPresets.all.first { it.id == "hu" }
        assertEquals(2, hu.pathHashSize)
        assertEquals(1, hu.pathHashMode)
        val ca = RadioPresets.all.first { it.id == "ca" }
        assertEquals(3, ca.pathHashSize)
        assertEquals(2, ca.pathHashMode)
        val nzLr = RadioPresets.all.first { it.id == "nz-lr" }
        assertEquals("New Zealand (Gisborne)", nzLr.name)
        assertEquals(1, nzLr.pathHashSize)
        assertEquals(0, nzLr.pathHashMode)
        val wcmesh = RadioPresets.all.first { it.id == "wcmesh" }
        assertEquals(3, wcmesh.pathHashSize)
        assertEquals(2, wcmesh.pathHashMode)
        val phillymesh = RadioPresets.all.first { it.id == "phillymesh" }
        assertEquals(2, phillymesh.pathHashSize)
        assertEquals(1, phillymesh.pathHashMode)
        val lvmesh = RadioPresets.all.first { it.id == "lvmesh" }
        assertEquals(2, lvmesh.pathHashSize)
        assertEquals(1, lvmesh.pathHashMode)
    }

    @Test
    fun presetsForTexasIncludePhillyMeshAndLvMesh() {
        val texas = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-TX",
            source = RegionSource.LOCATION,
        )
        val ids = RadioPresets.presets(forRegion = texas).map { it.id }
        assertTrue(ids.contains("phillymesh"))
        assertTrue(ids.contains("lvmesh"))
        assertTrue(ids.contains("us-ca"))
        val visible = RadioPresets.visiblePresets(texas, activePresetId = null).map { it.id }
        assertFalse(visible.contains("phillymesh"))
        assertFalse(visible.contains("lvmesh"))
        val pennsylvania = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-PA",
            source = RegionSource.LOCATION,
        )
        val pennsylvaniaIds = RadioPresets.visiblePresets(pennsylvania, activePresetId = null).map { it.id }
        assertTrue(pennsylvaniaIds.contains("phillymesh"))
        assertTrue(pennsylvaniaIds.contains("lvmesh"))
        val canada = RegionSelection(countryCode = "CA", source = RegionSource.LOCATION)
        val canadaIds = RadioPresets.presets(forRegion = canada).map { it.id }
        assertFalse(canadaIds.contains("phillymesh"))
        assertFalse(canadaIds.contains("lvmesh"))
    }

    private fun radioSnapshot(firmwareVersion: Int) = RadioDeviceSnapshot(
        frequencyKHz = 910_525,
        bandwidthHz = 62_500,
        spreadingFactor = 7,
        codingRate = 5,
        firmwareVersion = firmwareVersion,
    )
}

private fun RadioPreset.presetRf(): RadioPresets.RadioPresetRf = RadioPresets.RadioPresetRf(
    frequencyKHz = frequencyKHz,
    bandwidthHz = bandwidthHz,
    spreadingFactor = spreadingFactor,
    codingRate = codingRate,
)
