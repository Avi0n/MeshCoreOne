package one.meshcore.shared.contact

import kotlin.test.Test
import kotlin.test.assertEquals

class PingAnnouncementTest {
    @Test
    fun successNamesTheLatency() {
        assertEquals(
            "Ping successful, 42 milliseconds",
            pingAnnouncement(
                result = ContactPingResult.Success(latencyMs = 42, snrThere = 1.0, snrBack = 2.0),
                successTemplate = "Ping successful, %d milliseconds",
                failureMessage = "Ping failed",
            ),
        )
    }

    @Test
    fun anyFailureUsesTheFailedAnnouncement() {
        assertEquals(
            "Ping failed",
            pingAnnouncement(
                result = ContactPingResult.NoResponse,
                successTemplate = "Ping successful, %d milliseconds",
                failureMessage = "Ping failed",
            ),
        )
        assertEquals(
            "Ping failed",
            pingAnnouncement(
                result = ContactPingResult.Failure("timeout"),
                successTemplate = "Ping successful, %d milliseconds",
                failureMessage = "Ping failed",
            ),
        )
    }
}
