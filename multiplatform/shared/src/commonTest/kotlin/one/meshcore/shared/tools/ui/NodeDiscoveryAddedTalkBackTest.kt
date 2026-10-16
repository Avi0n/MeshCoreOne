package one.meshcore.shared.tools.ui

import one.meshcore.shared.contact.ui.discoveryAddedSpokenRes
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_discovery_added_accessibility
import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `NodeDiscoveryRowView` VoiceOver speaks "Already added to contacts", not "Added". */
class NodeDiscoveryAddedTalkBackTest {
    @Test
    fun anAddedHitSpeaksAlreadyAddedNotTheButtonLabel() {
        assertEquals(
            Res.string.contacts_discovery_added_accessibility,
            discoveryAddedSpokenRes(),
        )
    }
}
