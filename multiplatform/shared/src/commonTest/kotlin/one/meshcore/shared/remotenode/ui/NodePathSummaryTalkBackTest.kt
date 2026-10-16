package one.meshcore.shared.remotenode.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `NodePathSummaryLabel` VoiceOver speaks “Route: Direct” / “Route: {hops}”, not the raw line. */
class NodePathSummaryTalkBackTest {
    @Test
    fun zeroHopSpeaksRouteDirect() {
        assertEquals(
            "Route: Direct",
            nodePathSummaryAccessibilityLabel(
                pathHopCount = 0,
                pathDisplay = "Direct",
                directLabel = "Route: Direct",
                prefixTemplate = "Route: %s",
            ),
        )
    }

    @Test
    fun hopChainSpeaksRoutePrefix() {
        assertEquals(
            "Route: A3 → 7F",
            nodePathSummaryAccessibilityLabel(
                pathHopCount = 2,
                pathDisplay = "A3 → 7F",
                directLabel = "Route: Direct",
                prefixTemplate = "Route: %s",
            ),
        )
    }
}
