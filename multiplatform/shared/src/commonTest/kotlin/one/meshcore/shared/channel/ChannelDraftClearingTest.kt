package one.meshcore.shared.channel

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The channel-slot draft rule over the real [DraftStore], through the handler the host installs on each
 * connection's service. A channel draft is keyed by the volatile slot, so every path that frees a slot has
 * to drop it: a surviving draft is restored into whatever channel next occupies that slot, pre-filling text
 * written for one audience into a thread with a different one.
 */
class ChannelDraftClearingTest {
    private val radioID = ChannelTestIds.RADIO

    @Test
    fun deletingAChannelClearsItsDraftAndTheNextChannelInThatSlotStartsEmpty() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val service = testChannelService(session = FakeChannelSession())
        service.setSlotOccupantChanged { radio, slots -> drafts.clearChannelDrafts(radio, slots) }
        service.setChannelWithSecret(radioID, index = SLOT, name = "Ops", secret = secret16(1))
        val conversation = ChatConversationID.channel(radioID, SLOT)
        drafts.setDraft(DRAFT_TEXT, conversation)

        service.clearChannel(radioID, index = SLOT)

        assertNull(drafts.draft(conversation))
        // A different channel joined into the freed slot opens with an empty composer.
        service.setChannelWithSecret(radioID, index = SLOT, name = "Rescue", secret = secret16(2))
        assertNull(drafts.draftToApply(currentText = "", id = ChatConversationID.channel(radioID, SLOT)))
    }

    @Test
    fun aSyncPruneClearsTheDraftOfTheVacatedSlotOnly() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val store = InMemoryChannelStore()
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = SLOT))
        val session = FakeChannelSession()
        session.slots[KEPT_SLOT] = channelInfo(KEPT_SLOT, name = "Public")
        session.slots[SLOT] = emptySlot(SLOT) // the radio now reports the slot empty → prune
        val service = testChannelService(session = session, store = store)
        service.setSlotOccupantChanged { radio, slots -> drafts.clearChannelDrafts(radio, slots) }
        val pruned = ChatConversationID.channel(radioID, SLOT)
        val kept = ChatConversationID.channel(radioID, KEPT_SLOT)
        drafts.setDraft(DRAFT_TEXT, pruned)
        drafts.setDraft(KEPT_DRAFT_TEXT, kept)

        service.syncChannels(radioID, maxChannels = SLOT + 1, usePipelinedRead = false)

        assertNull(drafts.draft(pruned))
        assertEquals(KEPT_DRAFT_TEXT, drafts.draft(kept))
    }

    @Test
    fun aSecretChangeClearsTheDraftOfThatSlot() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val service = testChannelService(session = FakeChannelSession())
        service.setSlotOccupantChanged { radio, slots -> drafts.clearChannelDrafts(radio, slots) }
        service.setChannelWithSecret(radioID, index = SLOT, name = "Ops", secret = secret16(1))
        val conversation = ChatConversationID.channel(radioID, SLOT)
        drafts.setDraft(DRAFT_TEXT, conversation)

        service.setChannelWithSecret(radioID, index = SLOT, name = "Rescue", secret = secret16(2))

        assertNull(drafts.draft(conversation))
    }

    private companion object {
        const val SLOT = 1
        const val KEPT_SLOT = 0
        const val DRAFT_TEXT = "meet at the north ridge"
        const val KEPT_DRAFT_TEXT = "still typing"
    }
}
