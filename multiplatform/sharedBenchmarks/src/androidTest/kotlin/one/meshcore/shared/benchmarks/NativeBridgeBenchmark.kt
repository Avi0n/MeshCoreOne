package one.meshcore.shared.benchmarks

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.meshcore.RegionResolveHit
import one.meshcore.shared.meshcore.RegionResolveRow
import one.meshcore.shared.meshcore.RxLogDecryptKey
import one.meshcore.shared.meshcore.RxLogDecryptResult
import one.meshcore.shared.meshcore.RxLogDecryptRow
import one.meshcore.shared.meshcore.installAndroidRegionResolver
import one.meshcore.shared.meshcore.installAndroidRxLogDecryptor
import one.meshcore.shared.meshcore.platformRegionResolver
import one.meshcore.shared.meshcore.platformRxLogDecryptor
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the same complete native back-fill workloads as the functional bridge fixtures. */
@RunWith(AndroidJUnit4::class)
class NativeBridgeBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val resolver = run {
        installAndroidRegionResolver()
        platformRegionResolver()
    }
    private val decryptor = run {
        installAndroidRxLogDecryptor()
        platformRxLogDecryptor()
    }
    private val regionNames = listOf("\$private", "  Germany  ", "#Bavaria")
    private val regionPayload = hex("42deadbeef010203")
    private val regionRows = List(MAX_ROWS) { RegionResolveRow(regionPayload, GROUP_TEXT_BITS, hex("2b7c")) }
    private val channelPayload = hex("aba0e6215565cf0555d9e4a5e4824dc70018a6021e8232bb0509500bdf22a90e97978f")
    private val channelSecret = hex("8b3387e9c5cdea6ac9e5edbaa115cd72")
    private val decryptRows = List(MAX_ROWS) {
        RxLogDecryptRow.Channel(channelPayload, listOf(RxLogDecryptKey(CHANNEL_INDEX, channelSecret)))
    }

    @Before
    fun requireMeasurementConfiguration() {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("androidx.benchmark.suppressErrors").isNullOrBlank())
        check(!arguments.getString("androidx.benchmark.dryRunMode.enable").toBoolean())
        check(arguments.getString("androidx.benchmark.output.enable", "true").toBoolean())
        check(arguments.getString("androidx.benchmark.iterations").isNullOrBlank())
        check(!arguments.getString("androidx.benchmark.output.payload.runId").isNullOrBlank())
    }

    @Test
    fun regionBatched() {
        var results = emptyList<RegionResolveHit>()
        benchmarkRule.measureRepeated { results = resolver.resolve(regionNames, regionRows) }
        assertEquals(MAX_ROWS, results.size)
        results.forEach { assertEquals(RegionResolveHit.unique("Germany"), it) }
    }

    @Test
    fun regionPerRow() {
        val results = arrayOfNulls<List<RegionResolveHit>>(MAX_ROWS)
        benchmarkRule.measureRepeated {
            regionRows.forEachIndexed { index, row -> results[index] = resolver.resolve(regionNames, listOf(row)) }
        }
        results.forEach { assertEquals(listOf(RegionResolveHit.unique("Germany")), it) }
    }

    @Test
    fun decryptBatched() {
        var results = emptyList<RxLogDecryptResult>()
        benchmarkRule.measureRepeated { results = decryptor.decrypt(decryptRows) }
        assertEquals(MAX_ROWS, results.size)
        results.forEach(::assertDecrypted)
    }

    @Test
    fun decryptPerRow() {
        val results = arrayOfNulls<List<RxLogDecryptResult>>(MAX_ROWS)
        benchmarkRule.measureRepeated {
            decryptRows.forEachIndexed { index, row -> results[index] = decryptor.decrypt(listOf(row)) }
        }
        results.forEach { assertDecrypted(requireNotNull(it).single()) }
    }

    private fun assertDecrypted(result: RxLogDecryptResult) {
        val success = result as RxLogDecryptResult.Success
        assertEquals(CHANNEL_INDEX, success.keyIndex)
        assertEquals("Alice: Hello mesh!", success.text)
        assertEquals(SENDER_TIMESTAMP, success.senderTimestamp)
    }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val MAX_ROWS = 1000
        const val GROUP_TEXT_BITS = 5
        const val CHANNEL_INDEX = 3
        const val SENDER_TIMESTAMP = 1_703_123_456L
    }
}
