package one.meshcore.shared.remotenode

/**
 * Packed sensor-mode configuration sent when requesting telemetry. Mirrors the Swift `TelemetryModes`.
 * Each field is masked to 2 bits; [packed] is the single byte the protocol carries.
 */
data class TelemetryModes(val base: Int = 0, val location: Int = 0, val environment: Int = 0) {
    init {
        require(base == base and MODE_MASK) { "base must be 2-bit" }
        require(location == location and MODE_MASK) { "location must be 2-bit" }
        require(environment == environment and MODE_MASK) { "environment must be 2-bit" }
    }

    val packed: Int
        get() = (environment shl ENVIRONMENT_SHIFT) or (location shl LOCATION_SHIFT) or base

    companion object {
        private const val MODE_MASK = 0b11
        private const val LOCATION_SHIFT = 2
        private const val ENVIRONMENT_SHIFT = 4

        /** Masks each field to 2 bits, matching the Swift `init(base:location:environment:)`. */
        fun masked(base: Int, location: Int, environment: Int): TelemetryModes =
            TelemetryModes(base and MODE_MASK, location and MODE_MASK, environment and MODE_MASK)

        /** Unpacks a byte into its three 2-bit fields, inverse of [packed]. */
        fun fromPacked(packed: Int): TelemetryModes = TelemetryModes(
            base = packed and MODE_MASK,
            location = (packed shr LOCATION_SHIFT) and MODE_MASK,
            environment = (packed shr ENVIRONMENT_SHIFT) and MODE_MASK,
        )
    }
}
