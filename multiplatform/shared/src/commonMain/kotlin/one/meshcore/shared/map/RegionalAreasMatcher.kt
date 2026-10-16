package one.meshcore.shared.map

/**
 * Matches a normalized (trimmed, lowercased, diacritic-folded) administrative name to a region code,
 * the seam [RegionResolver] resolves subdivision and county codes through. Mirrors the static
 * `RegionalAreas.matchSubdivision` / `matchCounty` the iOS resolver calls.
 *
 * [RegionalAreas] implements this with the geographic catalog [RegionResolver] consumes. Keep the
 * matcher as the seam so tests can substitute a fake without loading the full table.
 */
interface RegionalAreasMatcher {
    /** The subdivision (state/province) code for a normalized admin-area name, or null if unmatched. */
    fun matchSubdivision(country: String, normalized: String?): String?

    /** The county key for a normalized county name within a subdivision, or null if unmatched. */
    fun matchCounty(country: String, state: String?, normalized: String?): String?
}
