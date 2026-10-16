package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.channel.ChannelTestIds
import one.meshcore.shared.channel.channelDto
import one.meshcore.shared.channel.channelInfo
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSlotOccupantChangedTest {
    private val radioID = ChannelTestIds.RADIO

    @Test
    fun applyClearsDraftsEvictsTheCoordinatorAndBumpsGeneration() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val registry = ChatCoordinatorRegistry(FakeCoordinatorStore(), backgroundScope)
        val generations = ChannelSlotGenerationTracker()
        val slot = ChatConversationID.channel(radioID, SLOT)
        val other = ChatConversationID.channel(radioID, OTHER_SLOT)
        drafts.setDraft("unsent", slot)
        drafts.setDraft("keep", other)
        registry.coordinator(slot)

        val editors = ChatDraftEditors(drafts, backgroundScope)
        applyChannelSlotOccupantChanged(radioID, setOf(SLOT), editors, registry, generations)

        assertNull(drafts.draft(slot))
        assertEquals("keep", drafts.draft(other))
        assertNull(registry.existingCoordinator(slot))
        assertEquals(1, generations.generation(slot))
        assertEquals(0, generations.generation(other))
    }

    @Test
    fun aSecretRewriteOfTheOpenChannelClosesIt() = runTest {
        val channel = channelDto(ChannelTestIds.fresh(), radioID, index = SLOT)
        val selected = ChatRoute.Channel(channel.id.value)
        assertTrue(
            shouldCloseOpenChannelChat(selected, radioID, setOf(SLOT)) { channel },
        )
    }

    @Test
    fun aVacatedOpenChannelClosesWhenTheRowIsGone() = runTest {
        val selected = ChatRoute.Channel("C0000000-0000-0000-0000-000000000001")
        assertTrue(
            shouldCloseOpenChannelChat(selected, radioID, setOf(SLOT)) { null },
        )
    }

    @Test
    fun anOpenChatOnAnotherSlotOrRadioStays() = runTest {
        val channel = channelDto(ChannelTestIds.fresh(), radioID, index = OTHER_SLOT)
        val selected = ChatRoute.Channel(channel.id.value)
        assertFalse(
            shouldCloseOpenChannelChat(selected, radioID, setOf(SLOT)) { channel },
        )
        assertFalse(
            shouldCloseOpenChannelChat(
                ChatRoute.Direct("contact"),
                radioID,
                setOf(SLOT),
            ) { error("unused") },
        )
    }

    @Test
    fun secretChangedIndicesIgnoresFirstSightingsAndSameSecretRenames() {
        val prior = listOf(
            channelDto(ChannelTestIds.fresh(), radioID, index = 1, secretByte = 0x11),
            channelDto(ChannelTestIds.fresh(), radioID, index = 2, secretByte = 0x22),
        )
        val configured = listOf(
            channelInfo(1, name = "Renamed", secretByte = 0x11),
            channelInfo(2, name = "Taken", secretByte = 0x99),
            channelInfo(3, name = "First", secretByte = 0x33),
        )
        assertEquals(setOf(2), secretChangedIndices(prior, configured))
    }

    private companion object {
        const val SLOT = 5
        const val OTHER_SLOT = 3
    }
}
