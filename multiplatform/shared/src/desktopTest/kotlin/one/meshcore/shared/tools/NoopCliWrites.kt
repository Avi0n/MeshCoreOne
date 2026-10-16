package one.meshcore.shared.tools

/** Records nothing and writes nothing; for tests whose subject is not the write path. */
internal open class NoopCliWrites : CliVerifiedWrites {
    override suspend fun setCoordinates(latitude: Double, longitude: Double) = Unit

    override suspend fun applyRadioParams(
        frequencyMHz: Double,
        bandwidthKHz: Double,
        spreadingFactor: Int,
        codingRate: Int,
        txPower: Int?,
    ) = Unit

    override suspend fun setTxPower(power: Int) = Unit

    override suspend fun setName(name: String): String = name

    override suspend fun setMultiAcks(value: Int) = Unit

    override suspend fun setPathHashMode(mode: Int) = Unit
}
