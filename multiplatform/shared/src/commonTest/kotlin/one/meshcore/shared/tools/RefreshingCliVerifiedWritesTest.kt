package one.meshcore.shared.tools

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RefreshingCliVerifiedWritesTest {
    @Test
    fun aSuccessfulWriteRefreshesSettings() = runTest {
        val inner = RecordingCliWrites()
        val refreshes = mutableListOf<String>()
        val writes = RefreshingCliVerifiedWrites(inner) { refreshes += "refresh" }

        writes.setCoordinates(1.0, 2.0)
        writes.applyRadioParams(869.5, 250.0, 11, 5, 22)
        writes.setTxPower(10)
        assertEquals("adopted", writes.setName("node"))
        writes.setMultiAcks(1)
        writes.setPathHashMode(2)

        assertEquals(6, refreshes.size)
        assertEquals(1, inner.coordinates)
        assertEquals(1, inner.radio)
        assertEquals(1, inner.txPower)
        assertEquals(1, inner.names)
        assertEquals(1, inner.multiAcks)
        assertEquals(1, inner.pathHash)
    }

    @Test
    fun aRefusedWriteDoesNotRefreshSettings() = runTest {
        val inner = RecordingCliWrites(fail = true)
        var refreshed = false
        val writes = RefreshingCliVerifiedWrites(inner) { refreshed = true }

        assertFailsWith<IllegalStateException> { writes.setTxPower(10) }
        assertFalse(refreshed)
        assertEquals(1, inner.txPower)
    }
}

private class RecordingCliWrites(private val fail: Boolean = false) : CliVerifiedWrites {
    var coordinates = 0
    var radio = 0
    var txPower = 0
    var names = 0
    var multiAcks = 0
    var pathHash = 0

    override suspend fun setCoordinates(latitude: Double, longitude: Double) {
        coordinates++
        if (fail) error("refused")
    }

    override suspend fun applyRadioParams(
        frequencyMHz: Double,
        bandwidthKHz: Double,
        spreadingFactor: Int,
        codingRate: Int,
        txPower: Int?,
    ) {
        radio++
        if (fail) error("refused")
    }

    override suspend fun setTxPower(power: Int) {
        txPower++
        if (fail) error("refused")
    }

    override suspend fun setName(name: String): String {
        names++
        if (fail) error("refused")
        return "adopted"
    }

    override suspend fun setMultiAcks(value: Int) {
        multiAcks++
        if (fail) error("refused")
    }

    override suspend fun setPathHashMode(mode: Int) {
        pathHash++
        if (fail) error("refused")
    }
}
