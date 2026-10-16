package one.meshcore.shared.settings

import one.meshcore.shared.backup.RegionSelection

/**
 * Geographic availability tier for a [RadioPreset]. Used by the recommendation algorithm to choose
 * the most-specific community-curated preset that matches the user's [RegionSelection].
 */
sealed class PresetAvailability {
    data class Continent(val region: RadioRegion) : PresetAvailability()
    data class Countries(val codes: Set<String>) : PresetAvailability()
    data class SubRegions(val country: String, val areas: Set<String>) : PresetAvailability()
    data class Counties(val country: String, val state: String, val keys: Set<String>) : PresetAvailability()
}
