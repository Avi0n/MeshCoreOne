package one.meshcore.shared.chat.ui

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal class JvmTimestampFormatter(locale: Locale, private val zone: ZoneId, override val unavailable: String) :
    TimestampFormatter {
    private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    private val secondsClock = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale)
    private val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val monthDay = DateTimeFormatter.ofPattern("MMM d", locale)

    override fun clock(epochSeconds: Long): String? = format(epochSeconds, clock)
    override fun clockWithSeconds(epochSeconds: Long): String? = format(epochSeconds, secondsClock)
    override fun date(epochSeconds: Long): String? = format(epochSeconds, date)
    override fun monthDay(epochSeconds: Long): String? = format(epochSeconds, monthDay)

    override fun monthDayClock(epochSeconds: Long): String? {
        val day = monthDay(epochSeconds)
        val time = clock(epochSeconds)
        return if (day != null && time != null) "$day $time" else null
    }

    override fun localEpochDay(epochSeconds: Long): Long? = try {
        Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate().toEpochDay()
    } catch (_: DateTimeException) {
        null
    }

    private fun format(epochSeconds: Long, formatter: DateTimeFormatter): String? = try {
        Instant.ofEpochSecond(epochSeconds).atZone(zone).format(formatter)
    } catch (_: DateTimeException) {
        null
    }
}
