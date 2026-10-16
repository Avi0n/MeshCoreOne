package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageIngestDumpTest {
    @Test
    fun ingestLinesMatchIos() {
        assertEquals("Skipping duplicate direct message", skippingDuplicateLine(INGEST_LOG_LABEL_DIRECT))
        assertEquals("Skipping duplicate channel message", skippingDuplicateLine(INGEST_LOG_LABEL_CHANNEL))
        assertEquals(
            "Unknown direct message textType raw=99; clamping to .plain",
            unknownTextTypeLine(INGEST_LOG_LABEL_DIRECT, 99),
        )
        assertEquals(
            "Unknown channel message textType raw=7; clamping to .plain",
            unknownTextTypeLine(INGEST_LOG_LABEL_CHANNEL, 7),
        )
    }
}
