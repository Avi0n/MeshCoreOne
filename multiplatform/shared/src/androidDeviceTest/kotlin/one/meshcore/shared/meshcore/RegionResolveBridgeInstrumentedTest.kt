package one.meshcore.shared.meshcore

import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.model.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the flood-region resolve entry point through the real `libMeshCoreBridgeKit.so` on a
 * device.
 *
 * The pure-JVM suites can only prove the envelopes; this one proves the HMAC itself lands where the
 * golden fixtures say it does, against the same independently generated transport codes the Swift
 * side is frozen on.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("MagicNumber") // The raw type bits are an exact resolver fixture.
class RegionResolveBridgeInstrumentedTest {

    private val resolver = run {
        installAndroidRegionResolver()
        platformRegionResolver()
    }

    private val regionNames = listOf("\$private", "  Germany  ", "#Bavaria")
    private val payload = hex("42deadbeef010203")

    private fun row(transportCode: String, payload: ByteArray = this.payload, bits: Int = GROUP_TEXT_BITS) =
        RegionResolveRow(payload = payload, payloadTypeBits = bits, transportCode = hex(transportCode))

    @Test
    fun everyGoldenVectorAttributesThroughTheBridge() {
        val regions = resolver.resolve(
            regionNames,
            listOf(
                row("2b7c"),
                row("d478"),
                row("9999"),
                row("2b"),
                row("2b7cffff"),
                row("0100", payload = hex("c0de00010e45")),
                row("2b7c", bits = 0xF5),
            ),
        )

        assertEquals(
            listOf("Germany", "#Bavaria", null, null, "Germany", "Germany", "Germany"),
            regions.map(RegionResolveHit::uniqueName),
        )
    }

    @Test
    fun aPrivateRegionNeverMatches() {
        // The $-prefixed name derives no scope key, so no code can be attributed to it.
        assertNull(resolver.resolve(listOf("\$private"), listOf(row("2b7c"))).single().uniqueName)
    }

    /** A full back-fill pass preserves every result compared with singleton native calls. */
    @Test
    fun aFullLogMatchesRowAtATime() {
        val rows = List(MAX_ROWS) { row("2b7c") }
        val batched = resolver.resolve(regionNames, rows)
        val perRow = rows.map { resolver.resolve(regionNames, listOf(it)).single() }

        assertEquals(MAX_ROWS, batched.count { it.uniqueName == "Germany" })
        assertEquals(batched, perRow)
    }

    private fun hex(text: String): ByteArray = requireNotNull(decodeHex(text))

    private companion object {
        const val GROUP_TEXT_BITS = 5

        /** The live RX-log buffer cap, so the test covers the worst back-fill pass. */
        const val MAX_ROWS = 1000
    }
}
