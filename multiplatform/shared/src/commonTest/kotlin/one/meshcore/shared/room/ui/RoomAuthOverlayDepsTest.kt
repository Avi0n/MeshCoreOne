package one.meshcore.shared.room.ui

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomAuthOverlayDepsTest {
    @Test
    fun conversationAuthUsesTheInjectedErrorText() {
        val deps = roomConversationAuthDependencies(
            RoomAuthSources(
                roomServerService = { null },
                contactService = { null },
                radioId = { RADIO },
                isRadioConnected = { true },
                nodePasswordStore = { null },
                errorText = { "Kein Funk" },
            ),
        )
        assertEquals("Kein Funk", deps.errorText(IllegalStateException("login")))
        assertNull(deps.discoveredNodeStore())
        assertNull(deps.userLocation())
    }

    private companion object {
        val RADIO = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    }
}
