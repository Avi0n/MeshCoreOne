package one.meshcore.shared.settings.ui

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RegionDiscoverOutcomeTest {
    @Test
    fun hitsOpenSelectionWithoutAskingForAStatusLine() = runTest {
        var asked = false
        val outcome = regionDiscoverOutcome(listOf("Bay Area")) {
            asked = true
            "unused"
        }
        assertEquals(listOf("Bay Area"), outcome.regionsToSelect)
        assertNull(outcome.statusMessage)
        assertFalse(asked)
    }

    @Test
    fun anEmptyHitAsksForTheStatusLine() = runTest {
        val outcome = regionDiscoverOutcome(emptyList()) { "No new regions" }
        assertNull(outcome.regionsToSelect)
        assertEquals("No new regions", outcome.statusMessage)
    }
}
