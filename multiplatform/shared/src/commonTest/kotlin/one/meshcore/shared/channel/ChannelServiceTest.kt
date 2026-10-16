package one.meshcore.shared.channel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the CRUD and create/join behavior of the iOS `ChannelService`: each flow issues the right
 * `setChannel` write with the exact secret bytes (via the W5-S1 derivations), the public slot-0 rule,
 * unconfigured-slot detection, and delete/clear semantics with the draft-clear + RX-cache side effects.
 */
class ChannelServiceTest {
    private val radioID = ChannelTestIds.RADIO

    @Test
    fun joinPublicWritesSlotZeroWithFixedSecret() = runTest {
        val session = FakeChannelSession()
        val service = testChannelService(session = session)

        service.setupPublicChannel(radioID)

        val call = session.setChannelCalls.single()
        assertEquals(ChannelSecret.PUBLIC_CHANNEL_SLOT, call.index)
        assertEquals(ChannelSecret.PUBLIC_CHANNEL_NAME, call.name)
        assertContentEquals(ChannelSecret.publicChannelSecret(), call.secret)
        assertTrue(service.hasPublicChannel(radioID))
    }

    @Test
    fun joinHashtagWritesHashtagNameAndDerivedSecret() = runTest {
        val session = FakeChannelSession()
        val service = testChannelService(session = session)
        val wire = ChannelSecret.hashtagChannelString("avion")

        service.setChannel(radioID, index = 1, name = wire, passphrase = wire)

        val call = session.setChannelCalls.single()
        assertEquals(1, call.index)
        assertEquals("#avion", call.name)
        // The # is part of the hashed input; the derived key must match any client typing the same hashtag.
        assertContentEquals(ChannelSecret.hashtagChannelSecret("avion"), call.secret)
    }

    @Test
    fun joinPrivateWritesGivenSecretVerbatim() = runTest {
        val session = FakeChannelSession()
        val service = testChannelService(session = session)
        val secret = ByteArray(ChannelSecret.SIZE_BYTES) { (it + 1).toByte() }

        service.setChannelWithSecret(radioID, index = 2, name = "Ops", secret = secret)

        val call = session.setChannelCalls.single()
        assertEquals(2, call.index)
        assertEquals("Ops", call.name)
        assertContentEquals(secret, call.secret)
        assertEquals("Ops", service.getChannel(radioID, 2)?.name)
    }

    @Test
    fun setChannelWithWrongSizedSecretThrowsAndDoesNotWrite() = runTest {
        val session = FakeChannelSession()
        val service = testChannelService(session = session)

        assertFailsWith<ChannelServiceError.SecretHashingFailed> {
            service.setChannelWithSecret(radioID, index = 1, name = "Bad", secret = ByteArray(15))
        }
        assertTrue(session.setChannelCalls.isEmpty())
    }

    @Test
    fun setChannelTruncatesNameToTheByteBudget() = runTest {
        val session = FakeChannelSession()
        val service = testChannelService(session = session)
        val longName = "x".repeat(40)

        service.setChannel(radioID, index = 1, name = longName, passphrase = "pw")

        assertEquals(31, session.setChannelCalls.single().name.length)
    }

    @Test
    fun writeRefreshesTheRxDecryptCache() = runTest {
        val session = FakeChannelSession()
        val rxCache = RecordingChannelListSink()
        val service = testChannelService(session = session, rxCache = rxCache)

        service.setChannelWithSecret(radioID, index = 1, name = "Ops", secret = secret16(1))

        assertEquals(listOf("Ops"), rxCache.updates.last().map { it.name })
    }

    @Test
    fun fetchChannelReturnsNullForEmptyAndNotFoundSlots() = runTest {
        val session = FakeChannelSession()
        session.slots[1] = emptySlot(1) // empty name + zero secret → unconfigured
        session.markUnconfigured(2) // device NOT_FOUND → unconfigured
        session.slots[3] = channelInfo(3)
        val service = testChannelService(session = session)

        assertNull(service.fetchChannel(1))
        assertNull(service.fetchChannel(2))
        assertEquals("ch3", service.fetchChannel(3)?.name)
    }

    @Test
    fun clearChannelVacatesTheSlotAndClearsDraftsAndMessages() = runTest {
        val session = FakeChannelSession()
        val store = InMemoryChannelStore()
        val drafts = RecordingSlotOccupantChanged()
        val service = testChannelService(session = session, store = store, slotOccupantChanged = drafts)
        service.setChannelWithSecret(radioID, index = 1, name = "Ops", secret = secret16(1))
        store.seedMessages(channelIndex = 1, count = 4)

        service.clearChannel(radioID, index = 1)

        // Slot cleared on the device (empty name + zero secret), local row deleted, drafts + messages cleared.
        val clearWrite = session.setChannelCalls.last()
        assertEquals("", clearWrite.name)
        assertContentEquals(ByteArray(ChannelSecret.SIZE_BYTES), clearWrite.secret)
        assertNull(service.getChannel(radioID, 1))
        assertEquals(listOf(radioID to setOf(1)), drafts.received)
        assertEquals(0, store.remainingMessageCount(1))
    }

    @Test
    fun clearChannelMessagesKeepsTheChannelButResetsPreviewAndUnread() = runTest {
        val session = FakeChannelSession()
        val store = InMemoryChannelStore()
        val service = testChannelService(session = session, store = store)
        store.seedChannel(
            channelDto(
                ChannelTestIds.fresh(),
                radioID,
                index = 1,
                unreadCount = 7,
                unreadMentionCount = 3,
                lastMessageDate = 100.0,
            ),
        )
        store.seedMessages(channelIndex = 1, count = 5)

        service.clearChannelMessages(radioID, channelIndex = 1)

        val channel = service.getChannel(radioID, 1)
        assertEquals(1, channel?.index) // channel kept
        assertNull(channel?.lastMessageDate)
        assertEquals(0, channel?.unreadCount)
        assertEquals(0, channel?.unreadMentionCount)
        assertEquals(0, store.remainingMessageCount(1))
    }

    @Test
    fun getActiveChannelsReturnsOnlyThoseWithMessages() = runTest {
        val store = InMemoryChannelStore()
        val service = testChannelService(store = store)
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = 1, lastMessageDate = 200.0))
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = 2, lastMessageDate = null))

        val active = service.getActiveChannels(radioID)

        assertEquals(listOf(1), active.map { it.index })
    }

    @Test
    fun hasPublicChannelIsFalseWhenSlotZeroAbsent() = runTest {
        val service = testChannelService()
        assertFalse(service.hasPublicChannel(radioID))
    }

    @Test
    fun setChannelFiresTheSlotHandlerOnANewSecretAndNotOnARenameOrFirstInsert() = runTest {
        val handler = RecordingSlotOccupantChanged()
        val service = testChannelService(slotOccupantChanged = handler)

        service.setChannelWithSecret(radioID, index = 5, name = "First", secret = secret16(1))
        service.setChannelWithSecret(radioID, index = 5, name = "Renamed", secret = secret16(1))
        assertEquals(emptyList(), handler.received)

        service.setChannelWithSecret(radioID, index = 5, name = "Replaced", secret = secret16(2))
        assertEquals(listOf(radioID to setOf(5)), handler.received)
    }
}
