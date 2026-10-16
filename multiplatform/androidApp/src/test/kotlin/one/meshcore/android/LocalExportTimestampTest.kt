package one.meshcore.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalExportTimestampTest {
    @Test
    fun matchesTheLocalExportBasenamePattern() {
        val now = Date(1_700_000_000_000L)
        val expected = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(now)
        assertEquals(expected, localExportTimestamp(now))
    }
}
