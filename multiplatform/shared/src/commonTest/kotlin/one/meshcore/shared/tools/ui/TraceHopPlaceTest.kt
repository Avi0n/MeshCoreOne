package one.meshcore.shared.tools.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TraceHopPlaceTest {
    @Test
    fun startWinsOverEndAndAMiddleHopIsRepeated() {
        assertEquals(TraceHopPlace.Started, traceHopPlace(isStartNode = true, isEndNode = true))
        assertEquals(TraceHopPlace.Started, traceHopPlace(isStartNode = true, isEndNode = false))
        assertEquals(TraceHopPlace.Received, traceHopPlace(isStartNode = false, isEndNode = true))
        assertEquals(TraceHopPlace.Repeated, traceHopPlace(isStartNode = false, isEndNode = false))
    }
}
