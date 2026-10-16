package one.meshcore.shared.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class WriteQueuePressureTest {
    @Test
    fun aSingleInFlightWriteDoesNotWarn() {
        val warnings = mutableListOf<Pair<Int, Int>>()
        val pressure = WriteQueuePressure { depth, consecutive -> warnings += depth to consecutive }
        pressure.enqueue()
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun theThirdBackloggedWriteWarnsWithDepthAndConsecutive() {
        val warnings = mutableListOf<Pair<Int, Int>>()
        val pressure = WriteQueuePressure { depth, consecutive -> warnings += depth to consecutive }
        repeat(4) { pressure.enqueue() }
        assertEquals(listOf(4 to 3), warnings)
    }

    @Test
    fun drainingToEmptyResetsTheConsecutiveCount() {
        val warnings = mutableListOf<Pair<Int, Int>>()
        val pressure = WriteQueuePressure { depth, consecutive -> warnings += depth to consecutive }
        repeat(4) { pressure.enqueue() }
        repeat(4) { pressure.dequeue() }
        pressure.enqueue()
        pressure.enqueue()
        assertEquals(listOf(4 to 3), warnings)
    }
}
