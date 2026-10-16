package one.meshcore.shared.ui

import one.meshcore.shared.remotenode.LocationReportFormat
import one.meshcore.shared.remotenode.Recency
import one.meshcore.shared.remotenode.RecencyUnit
import one.meshcore.shared.resources.loadComposeStringsXml
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Named-relative labels map to the remote-node "%d… ago" strings, matching iOS
 * `.relative(presentation: .named)` rather than compact `5m` / `2d`.
 */
class NamedRelativeLabelTest {
    @Test
    fun everyUnitResolvesToALocalizedResource() {
        assertEquals(
            "remotenodes_remote_nodes_status_seconds_ago",
            namedRelativeResource(Recency(RecencyUnit.SECONDS, 12)).key,
        )
        assertEquals(
            "remotenodes_remote_nodes_status_minutes_ago",
            namedRelativeResource(Recency(RecencyUnit.MINUTES, 5)).key,
        )
        assertEquals(
            "remotenodes_remote_nodes_status_hours_ago",
            namedRelativeResource(Recency(RecencyUnit.HOURS, 2)).key,
        )
        assertEquals(
            "remotenodes_remote_nodes_history_days_ago",
            namedRelativeResource(Recency(RecencyUnit.DAYS, 3)).key,
        )
    }

    @Test
    fun englishResourceValuesRenderTheAgoLabels() {
        val english = englishStrings()
        assertEquals("12s ago", render(Recency(RecencyUnit.SECONDS, 12), english))
        assertEquals("5m ago", render(Recency(RecencyUnit.MINUTES, 5), english))
        assertEquals("2h ago", render(Recency(RecencyUnit.HOURS, 2), english))
        assertEquals("3d ago", render(Recency(RecencyUnit.DAYS, 3), english))
    }

    @Test
    fun aRoomLastConnectedStampUsesHoursAgoNotCompactHours() {
        val recency = LocationReportFormat.relativeRecency((NOW - (NOW - 7_200)).toDouble())
        assertEquals(Recency(RecencyUnit.HOURS, 2), recency)
        assertEquals("2h ago", render(recency, englishStrings()))
    }

    private fun render(recency: Recency, english: Map<String, String>): String {
        val key = namedRelativeResource(recency).key
        val template = requireNotNull(english[key]) { "values/strings.xml declares no $key" }
        return formatTemplate(template, listOf(recency.value))
    }

    private fun englishStrings(): Map<String, String> = STRING_REGEX
        .findAll(loadComposeStringsXml("values"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val NOW = 1_700_000_000L
        val STRING_REGEX = Regex("""<string\s+name="([^"]+)">(.*?)</string>""")
    }
}
