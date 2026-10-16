package one.meshcore.shared.tools

/**
 * Runs [onVerified] after each successful inner write so Settings can re-read the Device row.
 * A refused write must not refresh: the cache would then show a value the radio rejected.
 */
class RefreshingCliVerifiedWrites(private val inner: CliVerifiedWrites, private val onVerified: suspend () -> Unit) :
    CliVerifiedWrites {
    override suspend fun setCoordinates(latitude: Double, longitude: Double) {
        inner.setCoordinates(latitude, longitude)
        onVerified()
    }

    override suspend fun applyRadioParams(
        frequencyMHz: Double,
        bandwidthKHz: Double,
        spreadingFactor: Int,
        codingRate: Int,
        txPower: Int?,
    ) {
        inner.applyRadioParams(frequencyMHz, bandwidthKHz, spreadingFactor, codingRate, txPower)
        onVerified()
    }

    override suspend fun setTxPower(power: Int) {
        inner.setTxPower(power)
        onVerified()
    }

    override suspend fun setName(name: String): String {
        val adopted = inner.setName(name)
        onVerified()
        return adopted
    }

    override suspend fun setMultiAcks(value: Int) {
        inner.setMultiAcks(value)
        onVerified()
    }

    override suspend fun setPathHashMode(mode: Int) {
        inner.setPathHashMode(mode)
        onVerified()
    }
}
