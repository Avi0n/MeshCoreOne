package one.meshcore.shared.meshcore

import one.meshcore.shared.model.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The Kotlin half of the flood-region resolve freeze. The rows below are the same ones the Swift
 * producer encodes into `fixtures/bridge/region/resolve-request.json`, so encoding them here must
 * reproduce that file byte for byte; the response fixture must then decode to the regions the Swift
 * side recorded.
 *
 * Every transport code in the vectors was produced by Python's `hmac`/`hashlib` against the firmware
 * rules, so neither language's implementation authored its own fixture.
 */
class RegionResolveEnvelopeTest {

    private val regionNames = listOf("\$private", "  Germany  ", "#Bavaria")
    private val payload = hex("42deadbeef010203")
    private val reservedPayload = hex("c0de00010e45")

    private val goldenRows: List<RegionResolveRow> = listOf(
        row(hex("2b7c")),
        row(hex("d478")),
        row(hex("9999")),
        row(hex("2b")),
        row(hex("2b7cffff")),
        row(hex("0100"), payload = reservedPayload),
        row(hex("2b7c"), payloadTypeBits = 0xF5),
    )

    private fun row(transportCode: ByteArray, payload: ByteArray = this.payload, payloadTypeBits: Int = 5) =
        RegionResolveRow(payload = payload, payloadTypeBits = payloadTypeBits, transportCode = transportCode)

    @Test
    fun requestEncodesToTheGoldenVector() {
        val golden = readBridgeFixtures("region").getValue("resolve-request").trimEnd('\n')
        assertEquals(golden, encodeRegionResolveRequest(regionNames, goldenRows).decodeToString())
    }

    @Test
    fun goldenResponseDecodesToTheRecordedRegions() {
        val golden = readBridgeFixtures("region").getValue("resolve-response")
        val hits = decodeRegionResolveResponse(golden, goldenRows.size)

        assertEquals(
            listOf("Germany", "#Bavaria", null, null, "Germany", "Germany", "Germany"),
            hits.map { it.uniqueName },
            "a unique match comes back trimmed; an unmatched, ambiguous, or unreadable code comes back null",
        )
        assertEquals(
            listOf(
                listOf("Germany"),
                listOf("#Bavaria"),
                emptyList(),
                emptyList(),
                listOf("Germany"),
                listOf("Germany"),
                listOf("Germany"),
            ),
            hits.map { it.matches },
        )
    }

    @Test
    fun aShortResultListIsPaddedRatherThanMisaligned() {
        val short = """{"kind":"regionResolve","payload":{"regions":["Germany"]},"v":1}"""
        val hits = decodeRegionResolveResponse(short, rowCount = 3)

        assertEquals(3, hits.size)
        assertEquals("Germany", hits[0].uniqueName)
        assertEquals(listOf("Germany"), hits[0].matches)
        assertNull(hits[1].uniqueName)
        assertNull(hits[2].uniqueName)
    }

    @Test
    fun anAmbiguousMatchListDecodesWithANullUniqueName() {
        val json = """{"kind":"regionResolve","payload":{"matches":[["de-by","de-hh"]],"regions":[null]},"v":1}"""
        val hit = decodeRegionResolveResponse(json, rowCount = 1).single()
        assertNull(hit.uniqueName)
        assertEquals(listOf("de-by", "de-hh"), hit.matches)
    }

    @Test
    fun aFailureEnvelopeReportsTheBridgeCodeAndMessage() {
        val failure = """{"kind":"regionResolveFailure","payload":{"errorCode":10,"message":"bad request"},"v":1}"""
        val error = assertFailsWith<BridgeDecodingException> { decodeRegionResolveResponse(failure, rowCount = 3) }
        assertEquals(true, error.message?.contains("bad request"))
    }

    @Test
    fun anUnknownEnvelopeKindIsRejected() {
        val alien = """{"kind":"somethingElse","payload":{},"v":1}"""
        assertFailsWith<BridgeDecodingException> { decodeRegionResolveResponse(alien, rowCount = 1) }
    }

    private fun hex(text: String): ByteArray = requireNotNull(decodeHex(text))
}
