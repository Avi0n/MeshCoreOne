package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageHandlerDumpTest {
    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @Test
    fun handlerLinesMatchIos() {
        assertEquals(
            "Wiring message handlers for device A0000000-0000-0000-0000-000000000001",
            wiringMessageHandlersLine(radioId),
        )
        assertEquals("Message handlers wired successfully", MESSAGE_HANDLERS_WIRED)
        assertEquals(
            "Starting discovery event monitoring for device A0000000-0000-0000-0000-000000000001",
            startingDiscoveryEventMonitoringLine(radioId),
        )
    }
}
