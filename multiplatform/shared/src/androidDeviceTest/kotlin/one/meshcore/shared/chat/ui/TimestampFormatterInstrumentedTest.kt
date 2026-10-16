package one.meshcore.shared.chat.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_timestamp_unavailable
import one.meshcore.shared.tools.rxLogReceivedLabel
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TimestampFormatterInstrumentedTest {
    private val epoch = Instant.parse("2026-08-15T20:13:00Z").epochSecond
    private val utc = ZoneId.of("UTC")

    @Test
    fun americanLocaleHonorsForced24HourClock() {
        val formatter = formatter(Locale.US, true)
        assertEquals("20:13", formatter.clock(epoch))
        assertEquals("20:13:00", formatter.clockWithSeconds(epoch))
        assertEquals("20:13:01", formatter.clockWithSeconds(epoch + 1))
    }

    @Test
    fun germanLocaleHonorsForced12HourClockAndMediumDate() {
        val formatter = formatter(Locale.GERMANY, false)
        assertEquals("8:13 PM", formatter.clock(epoch)?.normalizeSpaces())
        assertEquals("15.08.2026", formatter.date(epoch))
    }

    @Test
    fun monthDayUsesTheLocaleFieldOrder() {
        assertEquals("Aug 15", formatter(Locale.US, true).monthDay(epoch))
        assertEquals("15 Aug", formatter(Locale.UK, true).monthDay(epoch))
    }

    @Test
    fun capturedZoneUsesTheCorrectClockAndCalendarDay() {
        val formatter = formatter(Locale.US, true, ZoneId.of("America/Los_Angeles"))
        val midnight = Instant.parse("2026-12-01T00:30:00Z").epochSecond
        assertEquals("16:30", formatter.clock(midnight))
        assertEquals(20_787L, formatter.localEpochDay(midnight))
        formatter.localEpochDay(Instant.parse("2026-12-01T08:00:00Z").epochSecond)
        assertEquals(20_635L, formatter.localEpochDay(Instant.parse("2026-07-01T07:30:00Z").epochSecond))
    }

    @Test
    fun rxLabelsKeepTheDateAndSecondsAcrossNearbyMidnightSamples() {
        val formatter = formatter(Locale.US, true)
        val midnight = Instant.parse("2026-08-16T00:00:00Z").epochSecond * 1_000L
        val before = rxLogReceivedLabel(midnight - 1_000L, formatter)
        val first = rxLogReceivedLabel(midnight, formatter)
        val second = rxLogReceivedLabel(midnight + 30_000L, formatter)
        assertEquals("Aug 15, 2026 23:59:59", before)
        assertEquals("Aug 16, 2026 00:00:00", first)
        assertEquals("Aug 16, 2026 00:00:30", second)
        assertNotEquals(before, first)
        assertNotEquals(first, second)
    }

    @Test
    fun allTwelveLocalesHaveGeneratedFallbacksAndNativeClockFormats() {
        val original = Locale.getDefault()
        val originalFormat = Locale.getDefault(Locale.Category.FORMAT)
        val originalDisplay = Locale.getDefault(Locale.Category.DISPLAY)
        val reports = mutableListOf<String>()
        try {
            for (tag in LOCALES) {
                val locale = Locale.forLanguageTag(tag)
                Locale.setDefault(locale)
                val unavailable = runBlocking { getString(Res.string.android_timestamp_unavailable) }
                assertTrue("Generated fallback exists for $tag", unavailable.isNotBlank())
                assertNotEquals("android_timestamp_unavailable", unavailable)
                val fullClock = AndroidTimestampFormatter(locale, true, utc, unavailable)
                val shortClock = AndroidTimestampFormatter(locale, false, utc, unavailable)
                val nativeValues = listOf(
                    fullClock.clock(epoch),
                    fullClock.clockWithSeconds(epoch),
                    fullClock.date(epoch),
                    fullClock.monthDay(epoch),
                    fullClock.monthDayClock(epoch),
                )
                for (value in nativeValues) {
                    assertNotNull("Supported date is native for $tag", value)
                    assertTrue("Native format is nonempty for $tag", value.orEmpty().isNotBlank())
                }
                assertTrue("Explicit24-hour choice for $tag", fullClock.clock(epoch).orEmpty().contains("20:13"))
                assertTrue("Explicit12-hour choice for $tag", shortClock.clock(epoch).orEmpty().contains("8:13"))
                reports += "$tag unavailable=$unavailable native=$nativeValues short=${shortClock.clock(epoch)}"
            }
        } finally {
            Locale.setDefault(original)
            Locale.setDefault(Locale.Category.FORMAT, originalFormat)
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay)
            assertEquals(original, Locale.getDefault())
            assertEquals(originalFormat, Locale.getDefault(Locale.Category.FORMAT))
            assertEquals(originalDisplay, Locale.getDefault(Locale.Category.DISPLAY))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.cacheDir, "timestamp-native-locales.txt").writeText(reports.joinToString("\n"))
        }
    }

    private fun formatter(locale: Locale, is24Hour: Boolean, zone: ZoneId = utc): AndroidTimestampFormatter =
        AndroidTimestampFormatter(
            locale,
            is24Hour,
            zone,
            runBlocking { getString(Res.string.android_timestamp_unavailable) },
        )

    private fun String.normalizeSpaces(): String = replace('\u202F', ' ').replace('\u00A0', ' ')

    private companion object {
        val LOCALES = listOf("de", "en", "es", "fr", "it", "ko", "nl", "pl", "pt", "ru", "uk", "zh-Hans")
    }
}
