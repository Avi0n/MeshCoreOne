package one.meshcore.shared.chat.ui

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

internal class AndroidTimestampFormatter(
    locale: Locale,
    is24Hour: Boolean,
    private val zone: ZoneId,
    override val unavailable: String,
) : TimestampFormatter {
    private val clockSkeleton = if (is24Hour) CLOCK_24 else CLOCK_12
    private val secondsSkeleton = if (is24Hour) SECONDS_24 else SECONDS_12
    private val clock = skeleton(clockSkeleton, locale)
    private val secondsClock = skeleton(secondsSkeleton, locale)
    private val date = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).inZone()
    private val monthDay = skeleton(MONTH_DAY, locale)
    private val monthDayClock = skeleton(MONTH_DAY + clockSkeleton, locale)

    override fun clock(epochSeconds: Long): String? = format(epochSeconds, clock)
    override fun clockWithSeconds(epochSeconds: Long): String? = format(epochSeconds, secondsClock)
    override fun date(epochSeconds: Long): String? = format(epochSeconds, date)
    override fun monthDay(epochSeconds: Long): String? = format(epochSeconds, monthDay)
    override fun monthDayClock(epochSeconds: Long): String? = format(epochSeconds, monthDayClock)

    override fun localEpochDay(epochSeconds: Long): Long? = synchronized(date) {
        try {
            val instant = Instant.ofEpochSecond(epochSeconds)
            date.calendar.time = Date.from(instant)
            instant.atZone(zone).toLocalDate().toEpochDay()
        } catch (_: DateTimeException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun skeleton(fields: String, locale: Locale): DateFormat =
        DateFormat.getInstanceForSkeleton(fields, locale).inZone()

    private fun DateFormat.inZone(): DateFormat = apply {
        timeZone = TimeZone.getTimeZone(zone.id)
        setCalendarLenient(false)
    }

    private fun format(epochSeconds: Long, formatter: DateFormat): String? = synchronized(formatter) {
        try {
            formatter.format(Date.from(Instant.ofEpochSecond(epochSeconds)))
        } catch (_: DateTimeException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private const val CLOCK_24 = "Hm"
private const val CLOCK_12 = "hm"
private const val SECONDS_24 = "Hms"
private const val SECONDS_12 = "hms"
private const val MONTH_DAY = "MMMd"
