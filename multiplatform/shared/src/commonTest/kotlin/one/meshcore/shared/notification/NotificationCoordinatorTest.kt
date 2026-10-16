package one.meshcore.shared.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ActiveChannel
import one.meshcore.shared.chat.ChannelMessageNotice
import one.meshcore.shared.chat.RoomMessageNotice
import one.meshcore.shared.chat.reactions.ReactionNotice
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The coordinator's end-to-end gating, badge debounce, active-conversation suppression, and phantom
 * diagnostics — the Kotlin mirror of the posting-gate assertions in NotificationServiceTests plus the
 * shouldPost tests exercised through the real posting path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCoordinatorTest {
    @Test
    fun `direct message posts a notification`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.postDirectMessage(
            NotificationFixtures.contact(),
            "hi",
            NotificationFixtures.CONTACT,
            hasSelfMention = false,
        )
        assertEquals(1, poster.directPosts.size)
        assertEquals("Alice", poster.directPosts.single().title)
        assertEquals("hi", poster.directPosts.single().body)
    }

    @Test
    fun `muted contact does not post`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.postDirectMessage(
            NotificationFixtures.contact(isMuted = true),
            "hi",
            NotificationFixtures.CONTACT,
            hasSelfMention = false,
        )
        assertTrue(poster.directPosts.isEmpty())
    }

    @Test
    fun `active contact suppresses the post`() = runTest {
        val poster = RecordingPoster()
        val active = ActiveConversationState()
        active.setActiveContact(NotificationFixtures.CONTACT)
        val coordinator = coordinator(poster, active = active)
        coordinator.postDirectMessage(
            NotificationFixtures.contact(),
            "hi",
            NotificationFixtures.CONTACT,
            hasSelfMention = false,
        )
        assertTrue(poster.directPosts.isEmpty())
    }

    @Test
    fun `suppression flag blocks all posts`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.setSyncNotificationSuppression(true)
        assertTrue(coordinator.isSuppressingNotifications)
        coordinator.postDirectMessage(
            NotificationFixtures.contact(),
            "hi",
            NotificationFixtures.CONTACT,
            hasSelfMention = false,
        )
        assertTrue(poster.directPosts.isEmpty())
        coordinator.setSyncNotificationSuppression(false)
        assertFalse(coordinator.isSuppressingNotifications)
    }

    @Test
    fun `suppression watchdog force-clears after the timeout`() = runTest {
        val diagnostics = mutableListOf<String>()
        val coordinator = coordinator(RecordingPoster(), diagnostic = { diagnostics += it })
        coordinator.suppressionWatchdogTimeout = 50.milliseconds
        coordinator.setSyncNotificationSuppression(true)
        runCurrent()
        advanceTimeBy(49)
        assertTrue(coordinator.isSuppressingNotifications)
        assertTrue(diagnostics.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertFalse(coordinator.isSuppressingNotifications)
        assertEquals(
            listOf(SyncNotificationSuppressionWatchdog.FIRED_LINE),
            diagnostics,
        )
    }

    @Test
    fun `suppression watchdog cancels when the window closes`() = runTest {
        val diagnostics = mutableListOf<String>()
        val coordinator = coordinator(RecordingPoster(), diagnostic = { diagnostics += it })
        coordinator.suppressionWatchdogTimeout = 50.milliseconds
        coordinator.setSyncNotificationSuppression(true)
        coordinator.setSyncNotificationSuppression(false)
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertFalse(coordinator.isSuppressingNotifications)
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `unauthorized blocks all posts`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, authorized = false)
        coordinator.postDirectMessage(
            NotificationFixtures.contact(),
            "hi",
            NotificationFixtures.CONTACT,
            hasSelfMention = false,
        )
        assertTrue(poster.directPosts.isEmpty())
    }

    @Test
    fun `contact-messages preference off blocks the DM post`() = runTest {
        val poster = RecordingPoster()
        val prefs = FixedPreferences(NotificationPreferencesSnapshot.AllEnabled.copy(contactMessagesEnabled = false))
        val coordinator = coordinator(poster, preferences = prefs)
        coordinator.postDirectMessage(
            NotificationFixtures.contact(),
            "hi",
            NotificationFixtures.CONTACT,
            hasSelfMention = false,
        )
        assertTrue(poster.directPosts.isEmpty())
    }

    @Test
    fun `channel message composes sender-prefixed body and routes to the messages channel`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.postChannelMessage(
            ChannelMessageNotice(
                channel = NotificationFixtures.channel(index = 2),
                channelIndex = 2,
                radioID = NotificationFixtures.RADIO,
                senderNodeName = "Bob",
                text = "hello all",
                messageID = NotificationFixtures.CONTACT,
                hasSelfMention = false,
            ),
        )
        val post = poster.channelPosts.single()
        assertEquals("Bob: hello all", post.body)
        assertEquals(NotificationChannelKey.MESSAGES, post.channelKey)
    }

    @Test
    fun `mentions-only channel posts only a self-mention and uses the mentions channel`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        val channel = NotificationFixtures.channel(index = 3, level = NotificationLevel.MENTIONS_ONLY)
        coordinator.postChannelMessage(
            ChannelMessageNotice(
                channel = channel,
                channelIndex = 3,
                radioID = NotificationFixtures.RADIO,
                senderNodeName = "Bob",
                text = "no ping",
                messageID = NotificationFixtures.CONTACT,
                hasSelfMention = false,
            ),
        )
        assertTrue(poster.channelPosts.isEmpty())
        coordinator.postChannelMessage(
            ChannelMessageNotice(
                channel = channel,
                channelIndex = 3,
                radioID = NotificationFixtures.RADIO,
                senderNodeName = "Bob",
                text = "hey @me",
                messageID = NotificationFixtures.CONTACT,
                hasSelfMention = true,
            ),
        )
        assertEquals(NotificationChannelKey.MENTIONS, poster.channelPosts.single().channelKey)
    }

    @Test
    fun `active channel suppresses the post`() = runTest {
        val poster = RecordingPoster()
        val active = ActiveConversationState()
        active.setActiveChannel(ActiveChannel(NotificationFixtures.RADIO, 2))
        val coordinator = coordinator(poster, active = active)
        coordinator.postChannelMessage(
            ChannelMessageNotice(
                channel = NotificationFixtures.channel(index = 2),
                channelIndex = 2,
                radioID = NotificationFixtures.RADIO,
                senderNodeName = "Bob",
                text = "hi",
                messageID = NotificationFixtures.CONTACT,
                hasSelfMention = false,
            ),
        )
        assertTrue(poster.channelPosts.isEmpty())
    }

    @Test
    fun `badge update is debounced to a single write`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, unread = { UnreadCounts(contacts = 4, channels = 0, rooms = 0) })
        coordinator.updateBadge()
        coordinator.updateBadge()
        coordinator.updateBadge()
        advanceUntilIdle()
        assertEquals(listOf(4), poster.badges)
        assertEquals(4, coordinator.badgeCount.value)
    }

    @Test
    fun `badge is zero when the toggle is off`() = runTest {
        val poster = RecordingPoster()
        val prefs = FixedPreferences(NotificationPreferencesSnapshot.AllEnabled.copy(badgeEnabled = false))
        val coordinator = coordinator(poster, preferences = prefs, unread = { UnreadCounts(9, 9, 9) })
        coordinator.updateBadge()
        advanceUntilIdle()
        assertEquals(listOf(0), poster.badges)
    }

    @Test
    fun `opening a contact clears its delivered notifications`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.setActiveContact(NotificationFixtures.CONTACT)
        assertEquals(listOf(NotificationFixtures.CONTACT), poster.clearedContacts)
    }

    @Test
    fun `unresolved channel diagnostic dedups within the summary interval`() = runTest {
        val diagnostics = mutableListOf<String>()
        var clock = NotificationFixtures.NOW_SECONDS
        val coordinator = coordinator(RecordingPoster(), now = { clock }, diagnostic = { diagnostics += it })
        coordinator.recordUnresolvedChannel(channelIndex = 7, radioID = NotificationFixtures.RADIO, senderTimestamp = 1)
        coordinator.recordUnresolvedChannel(channelIndex = 7, radioID = NotificationFixtures.RADIO, senderTimestamp = 2)
        assertEquals(1, diagnostics.size)
        clock += 61.0
        coordinator.recordUnresolvedChannel(channelIndex = 7, radioID = NotificationFixtures.RADIO, senderTimestamp = 3)
        assertEquals(2, diagnostics.size)
    }

    @Test
    fun `unresolved channel records the iOS PersistentLogger dump lines`() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val logger = PersistentLogger(this, category = "NotificationCoordinator", record = { recorded += it })
        val coordinator = coordinator(RecordingPoster(), logger = logger)
        coordinator.recordUnresolvedChannel(
            channelIndex = 7,
            radioID = NotificationFixtures.RADIO,
            senderTimestamp = 42,
        )
        runCurrent()

        val messages = recorded.map { it.message }
        assertTrue(
            suppressingUnresolvedChannelLine(7, NotificationFixtures.RADIO, 42) in messages,
            "iOS logs every unresolved-channel suppress",
        )
        assertTrue(
            unresolvedChannelSummaryLine(listOf(7)) in messages,
            "iOS logs the rate-limited unresolved-channel summary",
        )
    }

    @Test
    fun `battery missed catch-up posts once for the whole batch`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.checkMissedBatteryThreshold("Radio", percentage = 8, isBatteryPresent = true)
        coordinator.checkMissedBatteryThreshold("Radio", percentage = 8, isBatteryPresent = true)
        assertEquals(1, poster.lowBatteryPosts.size)
        assertEquals(LowBatteryNotification("Radio", 8, playSound = true), poster.lowBatteryPosts.single())
    }

    @Test
    fun `battery threshold check posts once per crossing`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.checkBatteryThreshold("Radio", percentage = 15, isBatteryPresent = true)
        coordinator.checkBatteryThreshold("Radio", percentage = 15, isBatteryPresent = true)
        assertEquals(1, poster.lowBatteryPosts.size)
        assertEquals(LowBatteryNotification("Radio", 15, playSound = true), poster.lowBatteryPosts.single())
    }

    @Test
    fun `battery reaction and discovery honor the sound toggle`() = runTest {
        val poster = RecordingPoster()
        val prefs = FixedPreferences(NotificationPreferencesSnapshot.AllEnabled.copy(soundEnabled = false))
        val coordinator = coordinator(poster, preferences = prefs)
        coordinator.checkBatteryThreshold("Radio", percentage = 15, isBatteryPresent = true)
        coordinator.notifyReaction(
            ReactionNotice(
                reactorName = "Bob",
                emoji = "👍",
                messagePreview = "hi",
                messageID = NotificationFixtures.CONTACT,
                contactID = NotificationFixtures.CONTACT,
                channelIndex = null,
                radioID = NotificationFixtures.RADIO,
            ),
        )
        coordinator.notifyNewContact(NotificationFixtures.CONTACT, "Alice", ContactType.CHAT)
        assertFalse(poster.lowBatteryPosts.single().playSound)
        assertFalse(poster.reactionPosts.single().playSound)
        assertFalse(poster.newContactPosts.single().playSound)
    }

    @Test
    fun `battery threshold initialize posts when already low`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.initializeBatteryThreshold("Radio", percentage = 8, isBatteryPresent = true)
        assertEquals(1, poster.lowBatteryPosts.size)
    }

    @Test
    fun `battery threshold initialize is silent when level is healthy`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.initializeBatteryThreshold("Radio", percentage = 80, isBatteryPresent = true)
        assertTrue(poster.lowBatteryPosts.isEmpty())
    }

    @Test
    fun `low-battery preference off blocks the post`() = runTest {
        val poster = RecordingPoster()
        val prefs = FixedPreferences(NotificationPreferencesSnapshot.AllEnabled.copy(lowBatteryEnabled = false))
        val coordinator = coordinator(poster, preferences = prefs)
        coordinator.checkBatteryThreshold("Radio", percentage = 5, isBatteryPresent = true)
        assertTrue(poster.lowBatteryPosts.isEmpty())
    }

    @Test
    fun `unauthorized blocks the low-battery post`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, authorized = false)
        coordinator.checkBatteryThreshold("Radio", percentage = 5, isBatteryPresent = true)
        assertTrue(poster.lowBatteryPosts.isEmpty())
    }

    @Test
    fun `clearing battery thresholds re-arms a fresh connection`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.checkBatteryThreshold("Radio", percentage = 15, isBatteryPresent = true)
        coordinator.clearBatteryThresholds()
        coordinator.checkBatteryThreshold("Radio", percentage = 15, isBatteryPresent = true)
        assertEquals(2, poster.lowBatteryPosts.size)
    }

    @Test
    fun `reaction notifier posts through the poster`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.notifyReaction(
            ReactionNotice(
                reactorName = "Bob",
                emoji = "👍",
                messagePreview = "hi",
                messageID = NotificationFixtures.CONTACT,
                contactID = NotificationFixtures.CONTACT,
                channelIndex = null,
                radioID = NotificationFixtures.RADIO,
            ),
        )
        assertEquals(1, poster.reactionPosts.size)
        assertEquals("Bob", poster.reactionPosts.single().reactorName)
    }

    @Test
    fun `reaction preference off blocks the post`() = runTest {
        val poster = RecordingPoster()
        val prefs = FixedPreferences(
            NotificationPreferencesSnapshot.AllEnabled.copy(reactionsEnabled = false),
        )
        val coordinator = coordinator(poster, preferences = prefs)
        coordinator.notifyReaction(
            ReactionNotice(
                "Bob",
                "👍",
                "hi",
                NotificationFixtures.CONTACT,
                NotificationFixtures.CONTACT,
                null,
                NotificationFixtures.RADIO,
            ),
        )
        assertTrue(poster.reactionPosts.isEmpty())
    }

    @Test
    fun `open thread suppresses the reaction post`() = runTest {
        val poster = RecordingPoster()
        val active = ActiveConversationState()
        active.setActiveContact(NotificationFixtures.CONTACT)
        val coordinator = coordinator(poster, active = active)
        coordinator.notifyReaction(
            ReactionNotice(
                "Bob",
                "👍",
                "hi",
                NotificationFixtures.CONTACT,
                NotificationFixtures.CONTACT,
                null,
                NotificationFixtures.RADIO,
            ),
        )
        assertTrue(poster.reactionPosts.isEmpty())
    }

    @Test
    fun `sync suppression blocks the reaction post`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.setSyncNotificationSuppression(true)
        coordinator.notifyReaction(
            ReactionNotice(
                "Bob",
                "👍",
                "hi",
                NotificationFixtures.CONTACT,
                NotificationFixtures.CONTACT,
                null,
                NotificationFixtures.RADIO,
            ),
        )
        assertTrue(poster.reactionPosts.isEmpty())
    }

    @Test
    fun `new-contact notification routes the title by contact type`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.notifyNewContact(NotificationFixtures.CONTACT, "Alice", ContactType.CHAT)
        assertEquals(ContactType.CHAT, poster.newContactPosts.single().contactType)
    }

    @Test
    fun `new-contact master toggle off blocks every type`() = runTest {
        val poster = RecordingPoster()
        val prefs = FixedPreferences(
            NotificationPreferencesSnapshot.AllEnabled.copy(newContactDiscoveredEnabled = false),
        )
        val coordinator = coordinator(poster, preferences = prefs)
        coordinator.notifyNewContact(NotificationFixtures.CONTACT, "Alice", ContactType.CHAT)
        assertTrue(poster.newContactPosts.isEmpty())
    }

    @Test
    fun `new-contact per-type toggle off blocks only that type`() = runTest {
        val poster = RecordingPoster()
        val prefs = FixedPreferences(
            NotificationPreferencesSnapshot.AllEnabled.copy(discoveryRepeaterEnabled = false),
        )
        val coordinator = coordinator(poster, preferences = prefs)
        coordinator.notifyNewContact(NotificationFixtures.CONTACT, "Repeater", ContactType.REPEATER)
        assertTrue(poster.newContactPosts.isEmpty())
        coordinator.notifyNewContact(NotificationFixtures.CONTACT, "Alice", ContactType.CHAT)
        assertEquals(1, poster.newContactPosts.size)
    }

    @Test
    fun `room message composes sender-prefixed body and posts`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = NotificationFixtures.RADIO,
                senderName = "Bob",
                text = "status?",
                messageID = NotificationFixtures.CONTACT,
                notificationLevel = NotificationLevel.ALL,
            ),
        )
        val post = poster.roomPosts.single()
        assertEquals("Ops", post.roomName)
        assertEquals("Bob", post.senderName)
        assertEquals("status?", post.messageText)
    }

    @Test
    fun `room message with no author posts the bare text`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = NotificationFixtures.RADIO,
                senderName = null,
                text = "hi",
                messageID = NotificationFixtures.CONTACT,
                notificationLevel = NotificationLevel.ALL,
            ),
        )
        assertEquals(null, poster.roomPosts.single().senderName)
    }

    @Test
    fun `muted room level blocks the post`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = NotificationFixtures.RADIO,
                senderName = "Bob",
                text = "hi",
                messageID = NotificationFixtures.CONTACT,
                notificationLevel = NotificationLevel.MUTED,
            ),
        )
        assertTrue(poster.roomPosts.isEmpty())
    }

    @Test
    fun `mentions-only room level still posts`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = NotificationFixtures.RADIO,
                senderName = "Bob",
                text = "hi",
                messageID = NotificationFixtures.CONTACT,
                notificationLevel = NotificationLevel.MENTIONS_ONLY,
            ),
        )
        assertEquals(1, poster.roomPosts.size)
    }

    @Test
    fun `room-messages preference off blocks the post`() = runTest {
        val poster = RecordingPoster()
        val prefs = FixedPreferences(NotificationPreferencesSnapshot.AllEnabled.copy(roomMessagesEnabled = false))
        val coordinator = coordinator(poster, preferences = prefs)
        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = NotificationFixtures.RADIO,
                senderName = "Bob",
                text = "hi",
                messageID = NotificationFixtures.CONTACT,
                notificationLevel = NotificationLevel.ALL,
            ),
        )
        assertTrue(poster.roomPosts.isEmpty())
    }

    @Test
    fun `sync suppression blocks the room post`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.setSyncNotificationSuppression(true)
        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = NotificationFixtures.RADIO,
                senderName = "Bob",
                text = "hi",
                messageID = NotificationFixtures.CONTACT,
                notificationLevel = NotificationLevel.ALL,
            ),
        )
        assertTrue(poster.roomPosts.isEmpty())
    }

    @Test
    fun `unauthorized blocks the room post`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, authorized = false)
        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = NotificationFixtures.RADIO,
                senderName = "Bob",
                text = "hi",
                messageID = NotificationFixtures.CONTACT,
                notificationLevel = NotificationLevel.ALL,
            ),
        )
        assertTrue(poster.roomPosts.isEmpty())
    }

    @Test
    fun `foreground re-clear drops tray rows for the open DM`() = runTest {
        val poster = RecordingPoster()
        val active = ActiveConversationState()
        val coordinator = coordinator(poster, active = active)
        coordinator.setActiveContact(NotificationFixtures.CONTACT)
        poster.clearedContacts.clear()

        coordinator.clearDeliveredForActiveConversation()

        assertEquals(listOf(NotificationFixtures.CONTACT), poster.clearedContacts)
        assertTrue(poster.clearedChannels.isEmpty())
        assertTrue(poster.clearedRooms.isEmpty())
    }

    @Test
    fun `foreground re-clear drops tray rows for the open channel`() = runTest {
        val poster = RecordingPoster()
        val active = ActiveConversationState()
        val coordinator = coordinator(poster, active = active)
        coordinator.setActiveChannel(NotificationFixtures.RADIO, 2)
        poster.clearedChannels.clear()

        coordinator.clearDeliveredForActiveConversation()

        assertEquals(listOf(NotificationFixtures.RADIO to 2), poster.clearedChannels)
    }

    @Test
    fun `foreground re-clear is a no-op when no conversation is open`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)

        coordinator.clearDeliveredForActiveConversation()

        assertTrue(poster.clearedContacts.isEmpty())
        assertTrue(poster.clearedChannels.isEmpty())
        assertTrue(poster.clearedRooms.isEmpty())
    }

    @Suppress("LongParameterList")
    private fun TestScope.coordinator(
        poster: RecordingPoster,
        active: ActiveConversationState = ActiveConversationState(),
        preferences: NotificationPreferencesProvider = NotificationPreferencesProvider.AllEnabled,
        authorized: Boolean = true,
        unread: suspend () -> UnreadCounts = { UnreadCounts.Zero },
        now: () -> Double = { NotificationFixtures.NOW_SECONDS },
        diagnostic: (String) -> Unit = {},
        logger: PersistentLogger? = null,
        scope: CoroutineScope = this,
    ) = NotificationCoordinator(
        poster = poster,
        activeConversation = active,
        scope = scope,
        now = now,
        edges = NotificationCoordinatorEdges(
            preferences = preferences,
            isAuthorized = { authorized },
            unreadCounts = unread,
            diagnostic = diagnostic,
            logger = logger,
        ),
    )
}
