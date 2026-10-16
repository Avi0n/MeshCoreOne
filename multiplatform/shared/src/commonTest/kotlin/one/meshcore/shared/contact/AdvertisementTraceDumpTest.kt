package one.meshcore.shared.contact

import kotlin.test.Test
import kotlin.test.assertEquals

class AdvertisementTraceDumpTest {
    @Test
    fun receivedTraceDataLineMatchesIos() {
        assertEquals("Received trace data: tag=7, hops=2", receivedTraceDataLine(7, 2))
    }
}
