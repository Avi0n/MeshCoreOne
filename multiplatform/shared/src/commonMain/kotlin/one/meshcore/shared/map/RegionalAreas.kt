package one.meshcore.shared.map

import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.chat.translation.platformRegionDisplayName

/**
 * Geographic catalog translating geocoder results into the [RegionSelection] keys
 * [one.meshcore.shared.settings.RadioPresets.recommended] consumes. Mirrors iOS `RegionalAreas`.
 *
 * Vocabulary note: `RegionDiscoveryService` uses "region" to mean a firmware mesh region (a named
 * flood-routing scope on a repeater); this catalog's "region" is the user's geographic location.
 */
object RegionalAreas : RegionalAreasMatcher {
    /** An ISO 3166-2 subdivision matched from normalized geocoder admin-area names. */
    data class Subdivision(val id: String, val englishName: String, val normalizedNames: Set<String>)

    /** An ISO α-2 country with its optional subdivision catalog, for the manual region picker. */
    data class Country(val id: String, val subdivisions: List<Subdivision> = emptyList())

    /** Label for the administrative-area picker row. Hidden unless the catalog has more than one entry. */
    enum class AdministrativeAreaKind { STATE, PROVINCE }

    fun administrativeAreaKind(countryCode: String?): AdministrativeAreaKind =
        if (countryCode == "CA") AdministrativeAreaKind.PROVINCE else AdministrativeAreaKind.STATE

    val usSubdivisions: List<Subdivision> = listOf(
        us("AL", "Alabama"),
        us("AK", "Alaska"),
        us("AZ", "Arizona"),
        us("AR", "Arkansas"),
        us("CA", "California"),
        us("CO", "Colorado"),
        us("CT", "Connecticut"),
        us("DE", "Delaware"),
        us("DC", "District of Columbia", extra = listOf("washington dc", "washington d.c.")),
        us("FL", "Florida"),
        us("GA", "Georgia"),
        us("HI", "Hawaii"),
        us("ID", "Idaho"),
        us("IL", "Illinois"),
        us("IN", "Indiana"),
        us("IA", "Iowa"),
        us("KS", "Kansas"),
        us("KY", "Kentucky"),
        us("LA", "Louisiana"),
        us("ME", "Maine"),
        us("MD", "Maryland"),
        us("MA", "Massachusetts"),
        us("MI", "Michigan"),
        us("MN", "Minnesota"),
        us("MS", "Mississippi"),
        us("MO", "Missouri"),
        us("MT", "Montana"),
        us("NE", "Nebraska"),
        us("NV", "Nevada"),
        us("NH", "New Hampshire"),
        us("NJ", "New Jersey"),
        us("NM", "New Mexico"),
        us("NY", "New York"),
        us("NC", "North Carolina"),
        us("ND", "North Dakota"),
        us("OH", "Ohio"),
        us("OK", "Oklahoma"),
        us("OR", "Oregon"),
        us("PA", "Pennsylvania"),
        us("RI", "Rhode Island"),
        us("SC", "South Carolina"),
        us("SD", "South Dakota"),
        us("TN", "Tennessee"),
        us("TX", "Texas"),
        us("UT", "Utah"),
        us("VT", "Vermont"),
        us("VA", "Virginia"),
        us("WA", "Washington"),
        us("WV", "West Virginia"),
        us("WI", "Wisconsin"),
        us("WY", "Wyoming"),
    )

    val auSubdivisions: List<Subdivision> = listOf(
        au("ACT", "Australian Capital Territory"),
        au("NSW", "New South Wales"),
        au("NT", "Northern Territory"),
        au("QLD", "Queensland"),
        au("SA", "South Australia"),
        au("TAS", "Tasmania"),
        au("VIC", "Victoria"),
        au("WA", "Western Australia"),
    )

    /** Countries offered by the manual region picker, in the iOS `RegionalAreas.countries` order. */
    val countries: List<Country> = listOf(
        Country("US", usSubdivisions),
        Country("CA"),
        Country("CR"),
        Country("CL"),
        Country("BR"),
        Country("AU", auSubdivisions),
        Country("NZ"),
        Country("GB"),
        Country("IE"),
        Country("DE"),
        Country("FR"),
        Country("IT"),
        Country("ES"),
        Country("PT"),
        Country("NL"),
        Country("BE"),
        Country("CH"),
        Country("AT"),
        Country("CZ"),
        Country("PL"),
        Country("DK"),
        Country("SE"),
        Country("NO"),
        Country("FI"),
        Country("GR"),
        Country("HU"),
        Country("SK"),
        Country("RO"),
        Country("VN"),
        Country("TH"),
        Country("MY"),
        Country("SG"),
        Country("PH"),
        Country("ID"),
        Country("JP"),
        Country("KR"),
    )

    /**
     * Normalized US county names (lowercased, diacritic-folded, "county" suffix stripped), indexed by
     * ISO 3166-2 state code. Only states with county-scoped presets are filled.
     */
    val usCounties: Map<String, Set<String>> = mapOf(
        "US-CA" to setOf(
            "los angeles", "orange", "san diego", "riverside", "san bernardino",
            "ventura", "imperial", "kern", "santa barbara", "san luis obispo",
        ),
    )

    /**
     * Whether the manual picker shows a state/province list for [countryCode]. True when the catalog
     * has more than one subdivision (US, AU); a country-only pick (PT, CA) is complete without one.
     */
    fun showsSubdivisionPicker(countryCode: String?): Boolean = subdivisions(countryCode).size > 1

    /**
     * Catalog subdivisions for [countryCode]. Empty when the country is unknown or has no list.
     * Callers that show a picker sort by the localized display name.
     */
    fun subdivisions(countryCode: String?): List<Subdivision> {
        if (countryCode == null) return emptyList()
        return countries.firstOrNull { it.id == countryCode }?.subdivisions.orEmpty()
    }

    /**
     * The ISO 3166-2 subdivision code matching a normalized administrative-area name. Matches against
     * [Subdivision.normalizedNames], which holds both the English long form ("california") and the
     * short code ("ca") so either form a geocoder returns resolves. Null on miss; the recommendation
     * falls to country tier.
     */
    override fun matchSubdivision(country: String, normalized: String?): String? {
        if (normalized == null) return null
        val entry = countries.firstOrNull { it.id == country } ?: return null
        return entry.subdivisions.firstOrNull { normalized in it.normalizedNames }?.id
    }

    /**
     * The normalized county key when (country, state, name) all match the catalog. US counties have no
     * ISO identifier — the normalized name is the key.
     */
    override fun matchCounty(country: String, state: String?, normalized: String?): String? {
        if (country != US_COUNTRY_CODE || state == null || normalized == null) return null
        val counties = usCounties[state] ?: return null
        return normalized.takeIf { it in counties }
    }

    /**
     * [countries] sorted by localized country name. Callers pass the app language so a language
     * change reorders the picker; iOS freezes the order for the process because an app-language
     * change relaunches.
     */
    fun countriesSortedByLocalizedName(inLocale: String): List<Country> =
        countries.sortedBy { countryDisplayName(it.id, inLocale) }

    /**
     * Display name for a resolved region: "State, Country" except US/CA, which show the state alone.
     * Mirrors iOS `RegionalAreas.displayName`. Country names come from the platform locale;
     * [subdivisionNames] supplies Settings.strings values (English fallbacks when omitted).
     */
    fun displayName(
        region: RegionSelection,
        inLocale: String,
        subdivisionNames: Map<String, String> = emptyMap(),
    ): String {
        val countryName = countryDisplayName(region.countryCode, inLocale)
        val admin = region.administrativeAreaCode ?: return countryName
        val stateName = subdivisionDisplayName(admin, subdivisionNames) ?: admin
        return if (region.countryCode == US_COUNTRY_CODE || region.countryCode == "CA") {
            stateName
        } else {
            "$stateName, $countryName"
        }
    }

    /** Localized country name for [code] in [inLocale]; falls back to the code when the platform has none. */
    fun countryDisplayName(code: String, inLocale: String): String {
        val name = platformRegionDisplayName(code, inLocale)
        return name.ifBlank { code }
    }

    /** Localized subdivision name, then the English Settings.strings fallback, then null if unknown. */
    fun subdivisionDisplayName(code: String, subdivisionNames: Map<String, String> = emptyMap()): String? =
        subdivisionNames[code] ?: englishSubdivisionFallbacks[code]

    private const val US_COUNTRY_CODE = "US"

    private val englishSubdivisionFallbacks: Map<String, String> =
        (usSubdivisions + auSubdivisions).associate { it.id to it.englishName }
}

private fun us(code: String, name: String, extra: List<String> = emptyList()): RegionalAreas.Subdivision =
    subdivision(country = "US", code = code, name = name, extra = extra)

private fun au(code: String, name: String, extra: List<String> = emptyList()): RegionalAreas.Subdivision =
    subdivision(country = "AU", code = code, name = name, extra = extra)

private fun subdivision(country: String, code: String, name: String, extra: List<String>): RegionalAreas.Subdivision {
    val names = buildSet {
        add(name.lowercase())
        add(code.lowercase())
        extra.forEach { add(it.lowercase()) }
    }
    return RegionalAreas.Subdivision(id = "$country-$code", englishName = name, normalizedNames = names)
}
