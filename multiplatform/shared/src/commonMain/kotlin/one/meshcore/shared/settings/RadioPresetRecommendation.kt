package one.meshcore.shared.settings

import one.meshcore.shared.backup.RegionSelection

/** Locale ordering and the county-to-continent recommendation for [RadioPresets]. */
interface RadioPresetRecommendation {
    /** Presets filtered and sorted by preferred regions for [countryCode] (ISO α-2). */
    fun presetsForLocale(countryCode: String? = null): List<RadioPreset>

    /**
     * Most-specific community-curated preset for [region]: county → sub-region → country → continent.
     */
    fun recommended(forRegion: RegionSelection): RadioPreset?

    /** Alternatives list for the region's country (or continent if no country-level matches). */
    fun presets(forRegion: RegionSelection): List<RadioPreset>

    /**
     * Whether [preset] should appear in a manual picker for [region]. County- and sub-region-scoped
     * presets are gated to the matching admin area; country and continent tiers stay selectable.
     */
    fun isSelectable(preset: RadioPreset, region: RegionSelection?): Boolean

    /**
     * Settings → Radio picker for [region], plus the radio's current preset when that id is not
     * already in the regional list (traveler exception). Matches iOS `RadioPresets.visiblePresets`.
     */
    fun visiblePresets(region: RegionSelection?, activePresetId: String?): List<RadioPreset>
}

internal object RadioPresetRecommender : RadioPresetRecommendation {
    override fun presetsForLocale(countryCode: String?): List<RadioPreset> {
        val preferred = RadioRegion.regionsForCountry(countryCode)
        return RadioPresets.all.sortedWith(
            compareBy<RadioPreset> { preferred.indexOf(it.region).takeIf { i -> i >= 0 } ?: preferred.size }
                .thenBy { it.name },
        )
    }

    override fun recommended(forRegion: RegionSelection): RadioPreset? = matchCounty(forRegion)
        ?: matchSubRegion(forRegion)
        ?: matchCountry(forRegion)
        ?: matchContinent(forRegion)

    private fun matchCounty(region: RegionSelection): RadioPreset? {
        val admin = region.administrativeAreaCode
        val county = region.countyKey
        if (admin == null || county == null) {
            return null
        }
        return RadioPresets.recommendationOrder.firstOrNull { preset ->
            val a = preset.availability
            a is PresetAvailability.Counties &&
                a.country == region.countryCode &&
                a.state == admin &&
                county in a.keys
        }
    }

    private fun matchSubRegion(region: RegionSelection): RadioPreset? {
        val admin = region.administrativeAreaCode ?: return null
        return RadioPresets.recommendationOrder.firstOrNull { preset ->
            val a = preset.availability
            a is PresetAvailability.SubRegions &&
                a.country == region.countryCode &&
                admin in a.areas
        }
    }

    private fun matchCountry(region: RegionSelection): RadioPreset? =
        RadioPresets.recommendationOrder.firstOrNull { preset ->
            val a = preset.availability
            a is PresetAvailability.Countries && region.countryCode in a.codes
        }

    private fun matchContinent(region: RegionSelection): RadioPreset? {
        val continent = RadioPresets.continents[region.countryCode]
        return if (continent == null) {
            null
        } else {
            RadioPresets.recommendationOrder.firstOrNull { preset ->
                val a = preset.availability
                a is PresetAvailability.Continent && a.region == continent
            }
        }
    }

    override fun presets(forRegion: RegionSelection): List<RadioPreset> {
        val countryAndBelow = RadioPresets.all.filter { preset -> matchesCountryOrBelow(preset, forRegion.countryCode) }
        if (countryAndBelow.isNotEmpty()) {
            return countryAndBelow
        }
        val continent = RadioPresets.continents[forRegion.countryCode]
        return if (continent == null) {
            emptyList()
        } else {
            RadioPresets.all.filter { preset ->
                val a = preset.availability
                a is PresetAvailability.Continent && a.region == continent
            }
        }
    }

    private fun matchesCountryOrBelow(preset: RadioPreset, countryCode: String): Boolean =
        when (val a = preset.availability) {
            is PresetAvailability.Counties -> a.country == countryCode
            is PresetAvailability.SubRegions -> a.country == countryCode
            is PresetAvailability.Countries -> countryCode in a.codes
            is PresetAvailability.Continent -> false
        }

    override fun isSelectable(preset: RadioPreset, region: RegionSelection?): Boolean =
        when (val a = preset.availability) {
            is PresetAvailability.Counties ->
                region != null &&
                    region.countryCode == a.country &&
                    region.administrativeAreaCode == a.state &&
                    region.countyKey != null &&
                    region.countyKey in a.keys
            is PresetAvailability.SubRegions ->
                region != null &&
                    region.countryCode == a.country &&
                    region.administrativeAreaCode != null &&
                    region.administrativeAreaCode in a.areas
            is PresetAvailability.Countries, is PresetAvailability.Continent -> true
        }

    override fun visiblePresets(region: RegionSelection?, activePresetId: String?): List<RadioPreset> {
        val start = if (region != null) {
            val regional = presets(forRegion = region)
            if (regional.isEmpty()) presetsForLocale() else regional
        } else {
            presetsForLocale()
        }
        val result = start.filter { isSelectable(it, region) || it.id == activePresetId }.toMutableList()
        if (activePresetId != null && result.none { it.id == activePresetId }) {
            RadioPresets.all.firstOrNull { it.id == activePresetId }?.let { result += it }
        }
        return result
    }
}
