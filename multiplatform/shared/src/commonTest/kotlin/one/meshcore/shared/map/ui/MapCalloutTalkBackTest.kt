package one.meshcore.shared.map.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `ContactCalloutContent` / `DiscoveredNodeCalloutContent` VoiceOver hides the id-prefix hex. */
class MapCalloutTalkBackTest {
    @Test
    fun discoveredCalloutHeadlineSpeaksDiscoveredCommaName() {
        assertEquals(
            "Discovered, Beacon",
            discoveredCalloutHeadlineContentDescription(discoveredLabel = "Discovered", name = "Beacon"),
        )
    }
}
