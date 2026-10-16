package one.meshcore.shared.map.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MapContactDetailTest {
    private val labels = MapOutboundPathLabels(
        routingLabel = "Routing",
        floodValue = "Flood",
        pathLengthLabel = "Path Length",
        hopSingular = "1 hop",
        hopsTemplate = "%d hops",
    )

    @Test
    fun floodRoutedContactsShowRoutingFlood() {
        assertEquals(
            MapOutboundPathCopy(label = "Routing", value = "Flood"),
            mapOutboundPathCopy(isFloodRouted = true, hopCount = 4, labels = labels),
        )
    }

    @Test
    fun storedPathUsesSingularHopAndPluralTemplate() {
        assertEquals(
            MapOutboundPathCopy(label = "Path Length", value = "1 hop"),
            mapOutboundPathCopy(isFloodRouted = false, hopCount = 1, labels = labels),
        )
        assertEquals(
            MapOutboundPathCopy(label = "Path Length", value = "0 hops"),
            mapOutboundPathCopy(isFloodRouted = false, hopCount = 0, labels = labels),
        )
        assertEquals(
            MapOutboundPathCopy(label = "Path Length", value = "3 hops"),
            mapOutboundPathCopy(isFloodRouted = false, hopCount = 3, labels = labels),
        )
    }

    @Test
    fun publicKeyHexInsertsASpaceBetweenBytes() {
        assertEquals("0A 0B FF", spacedUppercaseHex(byteArrayOf(0x0A, 0x0B, 0xFF.toByte())))
        assertEquals("", spacedUppercaseHex(byteArrayOf()))
    }
}
