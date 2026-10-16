package one.meshcore.shared.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The user's geographic region selection, carried in the backup `userDefaults` block. Mirrors Swift
 * `RegionSelection`. Absent optional keys (`administrativeAreaCode`, `countyKey`) decode to null.
 */
@Serializable
data class RegionSelection(
    val countryCode: String,
    val administrativeAreaCode: String? = null,
    val countyKey: String? = null,
    val source: RegionSource,
) {
    companion object {
        /**
         * `null` means do not write: the tapped country is already selected. A new country is
         * always a country-only manual pick so a leftover state or county cannot travel with it.
         */
        fun afterChoosingCountry(newCountry: String, current: RegionSelection?): RegionSelection? {
            if (current != null && current.countryCode == newCountry) return null
            return RegionSelection(countryCode = newCountry, source = RegionSource.MANUAL)
        }

        /**
         * `null` means do not write: the tapped subdivision is already selected, or there is no
         * country to attach it to. A change drops [countyKey] so a California county cannot ride
         * onto Texas.
         */
        fun afterChoosingSubdivision(newSubdivision: String, current: RegionSelection?): RegionSelection? {
            if (current == null) return null
            if (current.administrativeAreaCode == newSubdivision) return null
            return RegionSelection(
                countryCode = current.countryCode,
                administrativeAreaCode = newSubdivision,
                source = RegionSource.MANUAL,
            )
        }
    }
}

/**
 * Source of a [RegionSelection]. Raw values are pinned to the Swift strings so a case rename cannot
 * silently change the on-disk format.
 */
@Serializable
enum class RegionSource {
    @SerialName("location")
    LOCATION,

    @SerialName("manual")
    MANUAL,
}
