package one.meshcore.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SendAdvertShortcutTest {
    @Test
    fun specsAreZeroHopThenFloodAndParseBack() {
        val specs = sendAdvertShortcutSpecs()
        assertEquals(2, specs.size)
        assertFalse(specs[0].flood)
        assertTrue(specs[1].flood)
        assertEquals(false, assertNotNull(MeshCoreUrlParser.parseSendAdvertUrl(specs[0].url)).flood)
        assertEquals(true, assertNotNull(MeshCoreUrlParser.parseSendAdvertUrl(specs[1].url)).flood)
        assertTrue(specs.all { it.shortcutId.startsWith(SEND_ADVERT_SHORTCUT_ID_PREFIX) })
    }

    @Test
    fun notConnectedAndFailedAreDistinctFromSent() {
        assertTrue(SendAdvertShortcutOutcome.NotConnected != SendAdvertShortcutOutcome.Sent)
        assertTrue(SendAdvertShortcutOutcome.Failed != SendAdvertShortcutOutcome.Sent)
    }
}
