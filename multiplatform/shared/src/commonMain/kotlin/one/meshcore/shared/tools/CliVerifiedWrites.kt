package one.meshcore.shared.tools

/**
 * The device writes the Tools CLI performs, in their verified, persisting form — the Kotlin counterpart
 * of the `*Verified` settings-service methods the Swift CLI calls.
 *
 * A raw session write neither reads the value back nor updates the Device row, so a refused write prints
 * OK while the app keeps using the old value: a wrong frequency takes a node off the mesh, and a
 * coordinate written while the on-board GPS is on is overwritten within seconds. Every implementation
 * must read back and persist, and throw when the radio reports something else.
 */
interface CliVerifiedWrites {
    /** Manual position: switches the on-board GPS off first, then writes and verifies. */
    suspend fun setCoordinates(latitude: Double, longitude: Double)

    /** LoRa parameters plus optional TX power, verified against the radio's read-back. */
    suspend fun applyRadioParams(
        frequencyMHz: Double,
        bandwidthKHz: Double,
        spreadingFactor: Int,
        codingRate: Int,
        txPower: Int?,
    )

    /** TX power only. Does not retune LoRa parameters, matching iOS `setTxPowerVerified`. */
    suspend fun setTxPower(power: Int)

    /** Node name; returns the name the radio actually took, which may be clamped to the byte budget. */
    suspend fun setName(name: String): String

    /** Multi-ack count, written through the shared other-params frame. */
    suspend fun setMultiAcks(value: Int)

    /** Routing hash width. A mode the radio never took would make every path the app builds unroutable. */
    suspend fun setPathHashMode(mode: Int)
}
