package one.meshcore.shared.tools.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RxLogCollapsedPathTest {
    @Test
    fun emptyPathSpeaksDirectAndShowsNoChips() {
        val collapsed = rxLogCollapsedPath(pathFacts(pathNodesEmpty = true, hopCount = 0))
        assertEquals(emptyList(), collapsed.chips)
        assertEquals("Direct", collapsed.spoken)
    }

    @Test
    fun unnamedHopsSpeakTheCount() {
        val singular = rxLogCollapsedPath(pathFacts(pathNodesEmpty = false, hopCount = 1))
        val plural = rxLogCollapsedPath(pathFacts(pathNodesEmpty = false, hopCount = 3))
        assertEquals("1 hop", singular.spoken)
        assertEquals("3 hops", plural.spoken)
        assertEquals(emptyList(), plural.chips)
    }

    @Test
    fun longPathsKeepBothEnds() {
        val labels = listOf("a", "b", "c", "d", "e", "f", "g")
        val collapsed = rxLogCollapsedPath(pathFacts(pathNodesEmpty = false, hopCount = 7, pathLabels = labels))
        assertEquals(listOf("a", "b", "c", "…", "e", "f", "g"), collapsed.chips)
        assertEquals("a b c … e f g", collapsed.spoken)
    }

    @Test
    fun previewQuotesDecodedText() {
        assertEquals(
            "\"seen\"",
            rxLogRowPreview(
                decodedText = "seen",
                typeName = "Text",
                version = 1,
                size = 4,
                bytesLabel = "bytes",
            ),
        )
    }

    @Test
    fun previewFallsBackToPayloadType() {
        assertEquals(
            "Advert v2, 12 bytes",
            rxLogRowPreview(
                decodedText = null,
                typeName = "Advert",
                version = 2,
                size = 12,
                bytesLabel = "bytes",
            ),
        )
    }

    @Test
    fun pathDetailUsesDirectHopCountOrNames() {
        assertEquals("Direct", rxLogPathDetail(pathFacts(pathNodesEmpty = true, hopCount = 0)))
        assertEquals(
            "1 hop [AA]",
            rxLogPathDetail(pathFacts(pathNodesEmpty = false, hopCount = 1, pathLabels = listOf("AA"))),
        )
        assertEquals("2 hops", rxLogPathDetail(pathFacts(pathNodesEmpty = false, hopCount = 2)))
    }
}

private fun pathFacts(pathNodesEmpty: Boolean, hopCount: Int, pathLabels: List<String> = emptyList()): RxLogPathFacts =
    RxLogPathFacts(
        pathNodesEmpty = pathNodesEmpty,
        hopCount = hopCount,
        pathLabels = pathLabels,
        directLabel = "Direct",
        hopSingular = "hop",
        hopPlural = "hops",
    )
