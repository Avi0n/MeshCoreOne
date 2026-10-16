package one.meshcore.shared.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentLoggerTest {
    @Test
    fun infoAndWarningRecordThroughTheSink() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val logger = PersistentLogger(
            scope = this,
            category = "ConnectionManager",
            now = { 1_000L },
            newId = { Uuid.normalized("E0000000-0000-0000-0000-000000000001") },
            record = { recorded += it },
        )

        logger.debug("no handler")
        logger.info("breaker closed")
        logger.warning("session.start failed")
        logger.error("Persisting envelope failed")
        logger.fault("container failed")

        assertEquals(4, recorded.size)
        assertEquals(DebugLogLevel.INFO, recorded[0].level)
        assertEquals("breaker closed", recorded[0].message)
        assertEquals("ConnectionManager", recorded[0].category)
        assertEquals(PersistentLogger.DEFAULT_SUBSYSTEM, recorded[0].subsystem)
        assertEquals(DebugLogLevel.WARNING, recorded[1].level)
        assertTrue(recorded[1].message.contains("session.start"))
        assertEquals(DebugLogLevel.ERROR, recorded[2].level)
        assertTrue(recorded[2].message.contains("Persisting envelope failed"))
        assertEquals(DebugLogLevel.FAULT, recorded[3].level)
    }
}
