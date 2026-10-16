package one.meshcore.shared.chat.ui

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.chat.BaseColorSlot
import one.meshcore.shared.chat.MessageFragment
import one.meshcore.shared.chat.MessageTextPayload
import one.meshcore.shared.chat.text.FormattedText
import one.meshcore.shared.chat.translation.applyComposerTranslate
import one.meshcore.shared.chat.translation.applySheetTranslate
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.testUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatThreadRootActionsTest {
    @Test
    fun translationSourceUsesOriginalTextFragments() {
        val content = listOf(
            textFragment(raw = "@[A1B2C3D4]", display = "@Alice"),
            MessageFragment.ReactionSummary("👍:2"),
            textFragment(raw = "second", display = "Second"),
        )

        assertEquals("@[A1B2C3D4]\nsecond", translationSourceText(content))
    }

    @Test
    fun floodPushWaitsForALiveChannelAndSession() = runBlocking {
        val pushed = mutableListOf<String>()
        val sync: suspend (String, ChannelFloodScope, String?, Boolean) -> Unit = { session, _, _, _ ->
            pushed += session
        }
        pushChannelFloodScope(floodPush(canPush = false, scope = ChannelFloodScope.Inherit, session = "radio"), sync)
        pushChannelFloodScope(floodPush(canPush = true, scope = null, session = "radio"), sync)
        pushChannelFloodScope(floodPush(canPush = true, scope = ChannelFloodScope.Inherit, session = null), sync)
        pushChannelFloodScope(floodPush(canPush = true, scope = ChannelFloodScope.Inherit, session = "radio"), sync)
        assertEquals(listOf("radio"), pushed.toList())
    }

    @Test
    fun reassignedSlotClearsAndLeaves() {
        val calls = mutableListOf<String>()
        leaveReassignedSlot(reassigned = false, clear = { calls += "clear" }, leave = { calls += "leave" })
        leaveReassignedSlot(reassigned = true, clear = { calls += "clear" }, leave = { calls += "leave" })
        clearComposerIfReassigned(reassigned = false) { calls += "teardown" }
        clearComposerIfReassigned(reassigned = true) { calls += "teardown" }
        assertEquals(listOf("clear", "leave", "teardown"), calls.toList())
    }

    @Test
    fun seedSkipsSelfBlankAndDisabledOffers() = runBlocking {
        val seeded = mutableListOf<Pair<Uuid, String>>()
        val items = listOf(
            ChatTranslationSeed(testUuid(1), outgoing = false, original = "hello"),
            ChatTranslationSeed(testUuid(2), outgoing = true, original = "mine"),
            ChatTranslationSeed(testUuid(3), outgoing = false, original = ""),
            ChatTranslationSeed(testUuid(4), outgoing = false, original = "again"),
        )
        seedChatThreadTranslations(offersEnabled = false, items = items) { id, text -> seeded += id to text }
        assertTrue(seeded.isEmpty())
        seedChatThreadTranslations(offersEnabled = true, items = items) { id, text -> seeded += id to text }
        assertEquals(listOf(testUuid(1) to "hello", testUuid(4) to "again"), seeded.toList())
    }

    @Test
    fun composerAndSheetTranslateFollowShowing() {
        val calls = mutableListOf<String>()
        applyComposerTranslate(false, true, { calls += "present" }, { calls += "perform" })
        applyComposerTranslate(false, false, { calls += "present" }, { calls += "perform" })
        applyComposerTranslate(true, true, { calls += "present" }, { calls += "perform" })
        applySheetTranslate(showing = true, presentSystem = {
            calls += "present"
        }, perform = { calls += "perform" })
        applySheetTranslate(showing = false, presentSystem = {
            calls += "present"
        }, perform = { calls += "perform" })
        assertEquals(listOf("present", "perform", "perform", "perform", "present"), calls.toList())
    }

    @Test
    fun infoDismissSkipsUntilChannelRadioAndService() = runBlocking {
        val current = channel("Ops")
        val fetched = channel("Ridge")
        val assigned = mutableListOf<String>()
        var loads = 0
        val load: suspend (Uuid, Int) -> ChannelDto? = { _, _ ->
            loads += 1
            fetched
        }
        applyChannelInfoDismiss(null, testUuid(1), true, load) { assigned += it.name }
        applyChannelInfoDismiss(current, null, true, load) { assigned += it.name }
        applyChannelInfoDismiss(current, testUuid(1), false, load) { assigned += it.name }
        assertEquals(0, loads)
        assertTrue(assigned.isEmpty())
        applyChannelInfoDismiss(current, testUuid(1), true, load) { assigned += it.name }
        applyChannelInfoDismiss(current, testUuid(1), true, { _, _ -> null }) { assigned += it.name }
        assertEquals(listOf("Ridge", "Ops"), assigned.toList())
    }

    @Test
    fun replyAppliesOnlyWhenAMessageExists() {
        val applied = mutableListOf<String>()
        applyChatReply(reply(hasMessage = false), apply = { applied += it })
        assertTrue(applied.isEmpty())
        applyChatReply(reply(hasMessage = true), apply = { applied += it })
        assertEquals(listOf("hello @[Alice] "), applied.toList())
    }

    private fun floodPush(canPush: Boolean, scope: ChannelFloodScope?, session: String?) = ChannelFloodPush(
        canPush = canPush,
        floodScope = scope,
        session = session,
        deviceDefaultFloodScopeName = "Bay Area",
        supportsUnscopedFloodSend = false,
    )

    private fun channel(name: String) = ChannelDto(
        id = testUuid(8),
        radioID = testUuid(9),
        index = 3,
        name = name,
        secret = byteArrayOf(1),
        isEnabled = true,
        unreadCount = 0,
    )

    private fun textFragment(raw: String, display: String) = MessageFragment.Text(
        MessageTextPayload(
            raw = raw,
            formattedText = FormattedText(displayString = display, spans = emptyList()),
            baseColor = BaseColorSlot.INCOMING,
            isOutgoing = false,
            currentUserName = "Me",
        ),
    )

    private fun reply(hasMessage: Boolean) = ChatReplyRequest(
        hasMessage = hasMessage,
        isChannel = false,
        senderNodeName = null,
        contactName = "Alice",
        unknownLabel = "Unknown",
        messageText = "hello",
        currentDraft = "hello",
        replyWithQuote = false,
    )
}
