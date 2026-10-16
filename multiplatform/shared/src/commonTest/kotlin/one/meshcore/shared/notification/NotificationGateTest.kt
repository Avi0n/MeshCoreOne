package one.meshcore.shared.notification

import one.meshcore.shared.model.NotificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shouldPost truth table (W2-spec §13.2/§13.3), mirroring the Swift posting guards and the
 * phantom-channel test pair in SyncCoordinatorMessageHandlerTests.
 */
class NotificationGateTest {
    @Test
    fun `direct posts when not muted and not active`() {
        assertTrue(NotificationGate.shouldPostDirect(isMuted = false, isActiveConversation = false))
    }

    @Test
    fun `direct is suppressed when muted`() {
        assertFalse(NotificationGate.shouldPostDirect(isMuted = true, isActiveConversation = false))
    }

    @Test
    fun `direct is suppressed for the active conversation`() {
        assertFalse(NotificationGate.shouldPostDirect(isMuted = false, isActiveConversation = true))
    }

    @Test
    fun `reaction is suppressed for the open thread`() {
        assertTrue(NotificationGate.shouldPostReaction(isActiveConversation = false))
        assertFalse(NotificationGate.shouldPostReaction(isActiveConversation = true))
    }

    @Test
    fun `channel level all posts regardless of mention`() {
        assertTrue(post(NotificationLevel.ALL, hasSelfMention = false))
        assertTrue(post(NotificationLevel.ALL, hasSelfMention = true))
    }

    @Test
    fun `channel level mentionsOnly posts only on a self-mention`() {
        assertFalse(post(NotificationLevel.MENTIONS_ONLY, hasSelfMention = false))
        assertTrue(post(NotificationLevel.MENTIONS_ONLY, hasSelfMention = true))
    }

    @Test
    fun `channel level muted never posts`() {
        assertFalse(post(NotificationLevel.MUTED, hasSelfMention = false))
        assertFalse(post(NotificationLevel.MUTED, hasSelfMention = true))
    }

    @Test
    fun `channel is suppressed for the active conversation even at level all`() {
        assertFalse(post(NotificationLevel.ALL, hasSelfMention = true, isActiveConversation = true))
    }

    @Test
    fun `phantom channel never posts at any level`() {
        NotificationLevel.entries.forEach { level ->
            assertFalse(
                NotificationGate.shouldPostChannel(
                    level = level,
                    hasSelfMention = true,
                    isActiveConversation = false,
                    channelResolved = false,
                ),
                "unresolved slot must not post at $level",
            )
        }
    }

    @Test
    fun `full channel matrix`() {
        // level x mention x active x resolved, spot-checking the product the pipeline relies on.
        assertEquals(true, post(NotificationLevel.ALL, hasSelfMention = false, isActiveConversation = false))
        assertEquals(false, post(NotificationLevel.ALL, hasSelfMention = false, channelResolved = false))
        assertEquals(true, post(NotificationLevel.MENTIONS_ONLY, hasSelfMention = true))
        assertEquals(false, post(NotificationLevel.MENTIONS_ONLY, hasSelfMention = true, isActiveConversation = true))
    }

    private fun post(
        level: NotificationLevel,
        hasSelfMention: Boolean,
        isActiveConversation: Boolean = false,
        channelResolved: Boolean = true,
    ): Boolean = NotificationGate.shouldPostChannel(level, hasSelfMention, isActiveConversation, channelResolved)
}
