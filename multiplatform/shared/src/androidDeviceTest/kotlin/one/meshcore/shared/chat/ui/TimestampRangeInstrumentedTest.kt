package one.meshcore.shared.chat.ui

import android.icu.util.Calendar
import android.icu.util.TimeZone
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.decodeBackupEnvelope
import one.meshcore.shared.backup.encodeBackupEnvelope
import one.meshcore.shared.backup.parseBackupImportPreview
import one.meshcore.shared.backup.previewPickedBackupJson
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_timestamp_unavailable
import one.meshcore.shared.tools.rxLogReceivedLabel
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TimestampRangeInstrumentedTest {
    private val epoch = Instant.parse("2026-08-15T20:13:00Z").epochSecond
    private val unavailable = runBlocking { getString(Res.string.android_timestamp_unavailable) }

    @Test
    fun unsupportedNativeDatesReturnNullAndRecoverAllPrimitives() {
        val formatter = formatter()
        val reports = mutableListOf<String>()
        for (unsupported in UNSUPPORTED_SECONDS) {
            assertNullPrimitives(formatter, unsupported)
            assertNormal(formatter)
            reports += "epoch=$unsupported allPrimitives=null normalRecovery=true"
        }
        record("primitives", reports)
    }

    @Test
    fun acceptedBackupEpochsKeepTheirValuesAndShowOneFallback() {
        val formatter = formatter()
        val reports = mutableListOf<String>()
        for (unsupported in BACKUP_SECONDS) {
            val json = backup(unsupported)
            val picked = previewPickedBackupJson(json)
            val pasted = parseBackupImportPreview(json)
            assertTrue(picked.manifestValid)
            assertTrue(pasted.manifestValid)
            assertEquals(unsupported, picked.exportDateEpochSeconds, 0.0)
            assertEquals(unsupported, pasted.exportDateEpochSeconds, 0.0)
            assertEquals(unavailable, formatMessageDetailDateTime(picked.exportDateEpochSeconds, formatter))
            assertEquals(unavailable, formatMessageDetailDateTime(pasted.exportDateEpochSeconds, formatter))
            val decoded = decodeBackupEnvelope(json)
            val roundTrip = decodeBackupEnvelope(encodeBackupEnvelope(decoded))
            assertEquals(unsupported, roundTrip.exportDate, 0.0)
            assertNormal(formatter)
            reports += "epoch=$unsupported picked=$picked pasted=$pasted roundTrip=${roundTrip.exportDate}"
        }
        assertEquals(unavailable, rxLogReceivedLabel(Long.MAX_VALUE, formatter))
        assertEquals(unavailable, rxLogReceivedLabel(Long.MIN_VALUE, formatter))
        record("backup", reports)
    }

    @Test
    fun unsupportedMatchingAndAdjacentDaysNeverBecomeRelativeLabels() {
        val formatter = formatter()
        val reports = mutableListOf<String>()
        for (unsupported in UNSUPPORTED_SECONDS.take(4)) {
            for (now in listOf(unsupported, unsupported + 86_400L, unsupported - 86_400L)) {
                val day = chatDayLabel(unsupported.toDouble(), formatter, now)
                assertEquals(ChatDayLabel.Kind.OTHER, day.kind)
                assertEquals(unavailable, day.absolute)
                assertEquals(
                    ConversationListTime.MonthDay(unavailable),
                    conversationListTime(unsupported, now, formatter),
                )
                reports += "epoch=$unsupported now=$now label=$day"
            }
        }
        assertEquals(
            ChatDayLabel.Kind.OTHER,
            chatDayLabel(epoch.toDouble(), formatter, Long.MAX_VALUE).kind,
        )
        record("classifiers", reports)
    }

    @Test
    fun nonfiniteDoubleHelpersReturnOneFallback() {
        val formatter = formatter()
        val reports = mutableListOf<String>()
        for (unsupported in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(unsupported.toTimestampEpochSecondOrNull())
            assertEquals(unavailable, chatClockLabel(unsupported, formatter))
            assertEquals(unavailable, chatMonthDayLabel(unsupported, formatter))
            assertNull(chartLocalEpochDay(unsupported, formatter))
            assertEquals(
                ChatDayLabel(ChatDayLabel.Kind.OTHER, unavailable),
                chatDayLabel(unsupported, formatter, epoch),
            )
            assertEquals(unavailable, formatMessageDetailDateTime(unsupported, formatter))
            assertNormal(formatter)
            reports += "epoch=$unsupported commonHelpers=unavailable normalRecovery=true"
        }
        record("nonfinite", reports)
    }

    @Test
    fun publicCalendarLimitsDefineSupportedAndUnsupportedDates() {
        val formatter = formatter()
        val reports = mutableListOf<String>()
        for (outsideMillis in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { isLenient = true }
            calendar.timeInMillis = outsideMillis
            val supportedMillis = calendar.timeInMillis
            assertNotEquals(outsideMillis, supportedMillis)
            val supported = supportedMillis / 1_000L
            assertEquals(supportedMillis, supported * 1_000L)
            val nativeValues = listOf(
                formatter.clock(supported),
                formatter.clockWithSeconds(supported),
                formatter.date(supported),
                formatter.monthDay(supported),
                formatter.monthDayClock(supported),
            )
            for (value in nativeValues) {
                assertNotNull("Public Calendar boundary $supported", value)
                assertTrue(value.orEmpty().isNotBlank())
            }
            assertNotNull(formatter.localEpochDay(supported))
            val unsupported = supported + if (outsideMillis > 0) 1L else -1L
            assertNullPrimitives(formatter, unsupported)
            assertNormal(formatter)
            reports += "outsideMillis=$outsideMillis boundaryMillis=$supportedMillis boundary=$supported " +
                "native=$nativeValues adjacentUnsupported=$unsupported normalRecovery=true"
        }
        record("native-boundaries", reports)
    }

    private fun assertNullPrimitives(formatter: TimestampFormatter, unsupported: Long) {
        assertNull("clock at $unsupported", formatter.clock(unsupported))
        assertNull("seconds clock at $unsupported", formatter.clockWithSeconds(unsupported))
        assertNull("date at $unsupported", formatter.date(unsupported))
        assertNull("month/day at $unsupported", formatter.monthDay(unsupported))
        assertNull("month/day clock at $unsupported", formatter.monthDayClock(unsupported))
        assertNull("local day at $unsupported", formatter.localEpochDay(unsupported))
    }

    private fun assertNormal(formatter: TimestampFormatter) {
        assertEquals("20:13", formatter.clock(epoch))
        assertEquals("20:13:00", formatter.clockWithSeconds(epoch))
        assertEquals("Aug 15, 2026", formatter.date(epoch))
        assertEquals("Aug 15", formatter.monthDay(epoch))
        val combined = formatter.monthDayClock(epoch).orEmpty()
        assertTrue(combined.contains("Aug 15"))
        assertTrue(combined.contains("20:13"))
        assertEquals(20_680L, formatter.localEpochDay(epoch))
    }

    private fun formatter(): AndroidTimestampFormatter =
        AndroidTimestampFormatter(Locale.US, true, ZoneId.of("UTC"), unavailable)

    private fun record(name: String, lines: List<String>) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "timestamp-range-$name.txt").writeText(lines.joinToString("\n"))
    }

    private fun backup(epoch: Double): String = """
        {"version":1,"exportDate":$epoch,"appVersion":"1","appBuild":"1",
        "manifest":{"deviceCount":0,"contactCount":0,"channelCount":0,"messageCount":0,
        "messageRepeatCount":0,"reactionCount":0,"roomMessageCount":0,"remoteNodeSessionCount":0,
        "savedTracePathCount":0,"blockedChannelSenderCount":0,"nodeStatusSnapshotCount":0},
        "devices":[],"contacts":[],"channels":[],"messages":[],"messageRepeats":[],"reactions":[],
        "roomMessages":[],"remoteNodeSessions":[],"savedTracePaths":[],"blockedChannelSenders":[],
        "nodeStatusSnapshots":[]}
    """.trimIndent()

    private companion object {
        val UNSUPPORTED_SECONDS = listOf(
            10_000_000_000_000_000L,
            -10_000_000_000_000_000L,
            1_000_000_000_000_000L,
            -1_000_000_000_000_000L,
            Long.MIN_VALUE,
            Long.MAX_VALUE,
        )
        val BACKUP_SECONDS = listOf(1e16, -1e16, 1e15, -1e15, 1e19, -1e19, 1e100, -1e100)
    }
}
