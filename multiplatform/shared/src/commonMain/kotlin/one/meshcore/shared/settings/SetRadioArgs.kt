package one.meshcore.shared.settings

/** Arguments for [one.meshcore.shared.meshcore.MeshSession.setRadio] from a preset. */
data class SetRadioArgs(
    val frequencyMHz: Double,
    val bandwidthKHz: Double,
    val spreadingFactor: Int,
    val codingRate: Int,
)
