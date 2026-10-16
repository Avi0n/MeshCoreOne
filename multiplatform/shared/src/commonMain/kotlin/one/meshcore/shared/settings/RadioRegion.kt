package one.meshcore.shared.settings

/**
 * Geographic regions for radio preset filtering. Display names match the Swift `RadioRegion` raw
 * values so UI copy and backup-adjacent code stay aligned.
 */
enum class RadioRegion(val displayName: String) {
    NORTH_AMERICA("North America"),
    SOUTH_AMERICA("South America"),
    EUROPE("Europe"),
    OCEANIA("Oceania"),
    ASIA("Asia"),
    ;

    val shortCode: String
        get() = when (this) {
            NORTH_AMERICA -> "NA"
            SOUTH_AMERICA -> "SA"
            EUROPE -> "EU"
            OCEANIA -> "AU"
            ASIA -> "AS"
        }

    companion object {
        /**
         * Regions ordered for a locale country (ISO-3166 α-2). Mirrors Swift
         * `RadioRegion.regionsForLocale`.
         */
        fun regionsForCountry(countryCode: String?): List<RadioRegion> = when (countryCode?.uppercase()) {
            "US", "CA", "CR" -> listOf(NORTH_AMERICA, EUROPE, OCEANIA, ASIA)
            "AU", "NZ" -> listOf(OCEANIA, NORTH_AMERICA, EUROPE, ASIA)
            "GB", "DE", "FR", "IT", "ES", "PT", "CH", "CZ", "IE", "NL", "BE", "AT", "HU", "SK" ->
                listOf(EUROPE, NORTH_AMERICA, OCEANIA, ASIA)
            "VN", "TH", "MY", "SG", "PH", "ID" -> listOf(ASIA, OCEANIA, EUROPE, NORTH_AMERICA)
            "CL", "BR" -> listOf(SOUTH_AMERICA, NORTH_AMERICA, EUROPE, OCEANIA, ASIA)
            else -> entries.toList()
        }
    }
}
