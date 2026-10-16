package one.meshcore.shared.contact

import androidx.compose.runtime.Composable
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.chatMonthDayLabel
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_relative_age_days
import one.meshcore.shared.resources.android_relative_age_hours
import one.meshcore.shared.resources.android_relative_age_minutes
import one.meshcore.shared.resources.chats_timestamp_now
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The localized template that renders [age]. Pure so the bucket-to-resource mapping is asserted in tests
 * without a composition, which is what keeps these labels out of Kotlin literals. The sub-minute bucket
 * reuses the iOS "now" wording; the counted buckets carry a `%d` the caller substitutes. A week or more
 * is [RelativeAge.OlderThanAWeek] and does not use a template.
 */
fun relativeAgeResource(age: RelativeAge): StringResource? = when (age) {
    RelativeAge.Now -> Res.string.chats_timestamp_now
    is RelativeAge.Minutes -> Res.string.android_relative_age_minutes
    is RelativeAge.Hours -> Res.string.android_relative_age_hours
    is RelativeAge.Days -> Res.string.android_relative_age_days
    RelativeAge.OlderThanAWeek -> null
}

/** The count [relativeAgeResource]'s template expects, or `null` for the count-less buckets. */
fun relativeAgeCount(age: RelativeAge): Long? = when (age) {
    RelativeAge.Now, RelativeAge.OlderThanAWeek -> null
    is RelativeAge.Minutes -> age.count
    is RelativeAge.Hours -> age.count
    is RelativeAge.Days -> age.count
}

/**
 * A compact localized relative-age label for an epoch-seconds timestamp. Under a week this is the iOS
 * "Now" wording or a `5m` / `3h` / `2d` template; a week or more is abbreviated month and day, matching
 * `RelativeTimestampText`. Substitution goes through [formatTemplate] because the templates carry a
 * bare `%d`, which Compose's own `stringResource(resource, args)` would render literally.
 */
@Composable
fun relativeAgeLabel(timestampSeconds: Long, nowSeconds: Long): String {
    val age = relativeAge(timestampSeconds, nowSeconds)
    return if (age is RelativeAge.OlderThanAWeek) {
        chatMonthDayLabel(timestampSeconds, formatter = LocalTimestampFormatter.current)
    } else {
        val template = stringResource(requireNotNull(relativeAgeResource(age)))
        relativeAgeCount(age)?.let { formatTemplate(template, listOf(it)) } ?: template
    }
}
