package one.meshcore.shared.contact

import one.meshcore.shared.chat.ui.chatMonthDayLabel
import one.meshcore.shared.resources.loadComposeStringsXml
import one.meshcore.shared.timestampTestFormatter
import one.meshcore.shared.ui.formatTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The relative-age buckets and, more importantly, that every bucket renders from a string resource
 * rather than a Kotlin literal. Compose's own resource reads need a composition, so the localization
 * guarantee is asserted through the pure [relativeAgeResource] mapping the composable delegates to,
 * plus the English resource values those names resolve to.
 */
class RelativeAgeTest {
    @Test
    fun bucketsFollowTheMinuteHourDayWeekThresholds() {
        assertEquals(RelativeAge.Now, relativeAge(NOW - 59, NOW))
        assertEquals(RelativeAge.Minutes(1), relativeAge(NOW - 60, NOW))
        assertEquals(RelativeAge.Minutes(59), relativeAge(NOW - 3_599, NOW))
        assertEquals(RelativeAge.Hours(1), relativeAge(NOW - 3_600, NOW))
        assertEquals(RelativeAge.Hours(23), relativeAge(NOW - 86_399, NOW))
        assertEquals(RelativeAge.Days(1), relativeAge(NOW - 86_400, NOW))
        assertEquals(RelativeAge.Days(6), relativeAge(NOW - 604_799, NOW))
        assertEquals(RelativeAge.OlderThanAWeek, relativeAge(NOW - 604_800, NOW))
        assertEquals(RelativeAge.OlderThanAWeek, relativeAge(NOW - 4 * 604_800, NOW))
    }

    /** A radio clock ahead of the phone must read as "now", never as a negative age. */
    @Test
    fun aFutureTimestampClampsToNow() {
        assertEquals(RelativeAge.Now, relativeAge(NOW + 10_000, NOW))
    }

    @Test
    fun everyBucketResolvesToALocalizedResource() {
        assertEquals("chats_timestamp_now", relativeAgeResource(RelativeAge.Now)?.key)
        assertEquals("android_relative_age_minutes", relativeAgeResource(RelativeAge.Minutes(5))?.key)
        assertEquals("android_relative_age_hours", relativeAgeResource(RelativeAge.Hours(3))?.key)
        assertEquals("android_relative_age_days", relativeAgeResource(RelativeAge.Days(2))?.key)
        assertNull(relativeAgeResource(RelativeAge.OlderThanAWeek))
    }

    @Test
    fun onlyTheCountedBucketsCarryASubstitutionArgument() {
        assertNull(relativeAgeCount(RelativeAge.Now))
        assertEquals(5L, relativeAgeCount(RelativeAge.Minutes(5)))
        assertEquals(3L, relativeAgeCount(RelativeAge.Hours(3)))
        assertEquals(2L, relativeAgeCount(RelativeAge.Days(2)))
        assertNull(relativeAgeCount(RelativeAge.OlderThanAWeek))
    }

    /**
     * Resolves each bucket the way the composable does — English resource value plus `formatTemplate` —
     * so the labels are proven to come out of `values/strings.xml`. The counted templates carry a bare
     * `%d`, which Compose's own `stringResource(resource, args)` would render literally.
     */
    @Test
    fun englishResourceValuesRenderTheCompactLabels() {
        val english = englishStrings()
        assertEquals("Now", render(RelativeAge.Now, english))
        assertEquals("5m", render(RelativeAge.Minutes(5), english))
        assertEquals("3h", render(RelativeAge.Hours(3), english))
        assertEquals("2d", render(RelativeAge.Days(2), english))
        assertNull(relativeAgeResource(RelativeAge.OlderThanAWeek))
    }

    @Test
    fun aWeekOrMoreUsesMonthAndDayWithoutAYear() {
        val stamp = NOW - 604_800
        val label = chatMonthDayLabel(stamp, formatter = timestampTestFormatter)
        assertTrue(label.isNotBlank())
        assertTrue(!Regex("\\d{4}").containsMatchIn(label), "month+day must omit the year: $label")
    }

    private fun render(age: RelativeAge, english: Map<String, String>): String {
        val key = requireNotNull(relativeAgeResource(age)?.key) { "counted buckets need a resource" }
        val template = requireNotNull(english[key]) { "values/strings.xml declares no $key" }
        val count = relativeAgeCount(age) ?: return template
        return formatTemplate(template, listOf(count))
    }

    private fun englishStrings(): Map<String, String> = STRING_REGEX
        .findAll(loadComposeStringsXml("values"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val NOW = 1_700_000_000L
        val STRING_REGEX = Regex("""<string\s+name="([^"]+)">(.*?)</string>""")
    }
}
