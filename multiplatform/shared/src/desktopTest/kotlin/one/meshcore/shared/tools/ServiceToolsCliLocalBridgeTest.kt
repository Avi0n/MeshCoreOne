package one.meshcore.shared.tools

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.FakeMeshSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ServiceToolsCliLocalBridgeTest {
    @Test
    fun syncClockWritesTheClockTimeToTheSessionAndReturnsIt() = runTest {
        val fixedEpoch = 1_700_000_000L
        val session = FakeMeshSession()
        val bridge = ServiceToolsCliLocalBridge(
            session,
            clock = fixedClock(fixedEpoch),
            writes = NoopCliWrites(),
        )

        val returned = bridge.syncClock()

        assertEquals(fixedEpoch, returned, "syncClock returns the epoch it wrote")
        assertEquals(fixedEpoch, session.lastSetTimeEpoch, "session.setTime received the same epoch")
    }

    @Test
    fun setLatitudeGoesThroughTheVerifiedManualWrite() = runTest {
        val session = FakeMeshSession()
        val written = mutableListOf<Pair<Double, Double>>()
        val bridge = ServiceToolsCliLocalBridge(
            session,
            writes = object : NoopCliWrites() {
                override suspend fun setCoordinates(latitude: Double, longitude: Double) {
                    written += latitude to longitude
                }
            },
        )

        bridge.setLatitude(NEW_LATITUDE)

        // A raw session write would let a radio with its on-board GPS on overwrite the value seconds later,
        // so `set lat` must go through the verified manual write that switches the GPS off first.
        assertEquals(listOf(NEW_LATITUDE to session.getSelfInfo().longitude), written)
    }

    private fun fixedClock(epochSeconds: Long): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds)
    }

    @Test
    fun radioWritesGoThroughTheVerifiedPersistingPath() = runTest {
        val session = FakeMeshSession()
        val applied = mutableListOf<String>()
        val appliedPower = mutableListOf<Int>()
        val bridge = ServiceToolsCliLocalBridge(
            session,
            writes = object : NoopCliWrites() {
                override suspend fun applyRadioParams(
                    frequencyMHz: Double,
                    bandwidthKHz: Double,
                    spreadingFactor: Int,
                    codingRate: Int,
                    txPower: Int?,
                ) {
                    applied += "$frequencyMHz|$bandwidthKHz|$spreadingFactor|$codingRate|$txPower"
                }

                override suspend fun setTxPower(power: Int) {
                    appliedPower += power
                }
            },
        )
        val current = session.getSelfInfo()

        bridge.setFrequency(NEW_FREQUENCY_MHZ)
        bridge.setTxPower(NEW_TX_POWER)

        // The raw session write neither reads back nor persists, so a refused `set freq` printed OK while
        // the app kept routing at the old parameters. `set tx` is power-only, matching iOS
        // `setTxPowerVerified` — retuning LoRa just to change power would drop a live mesh.
        assertEquals(
            listOf(
                "$NEW_FREQUENCY_MHZ|${current.radioBandwidth}|${current.radioSpreadingFactor}|${current.radioCodingRate}|null",
            ),
            applied,
        )
        assertEquals(listOf(NEW_TX_POWER), appliedPower)
    }

    private companion object {
        const val NEW_LATITUDE = 52.5
        const val NEW_FREQUENCY_MHZ = 869.525
        const val NEW_TX_POWER = 17
    }
}
