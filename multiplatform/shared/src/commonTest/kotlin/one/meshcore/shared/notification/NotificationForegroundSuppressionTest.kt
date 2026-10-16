package one.meshcore.shared.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.app.AppLifecycleController
import one.meshcore.shared.chat.ChannelMessageNotice
import one.meshcore.shared.chat.RoomMessageNotice
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The foreground half of the active-conversation gate. iOS suppresses the banner for the thread on screen
 * inside `userNotificationCenter(_:willPresent:)`, which the system calls only for a foregrounded app, so
 * the same thread still raises banners once the app is away — the case that matters when a responder locks
 * the phone on an open chat. The claim itself survives the round trip, so returning to the foreground
 * suppresses again with nothing having re-claimed it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationForegroundSuppressionTest {
    @Test
    fun `a backgrounded app notifies for the DM still on screen`() = runTest {
        val app = ForegroundHarness(this)
        app.coordinator.setActiveContact(NotificationFixtures.CONTACT)
        app.background()

        app.postDirect()

        assertEquals(1, app.poster.directPosts.size)
    }

    @Test
    fun `a backgrounded app notifies for the channel still on screen`() = runTest {
        val app = ForegroundHarness(this)
        app.coordinator.setActiveChannel(NotificationFixtures.RADIO, CHANNEL_INDEX)
        app.background()

        app.postChannel()

        assertEquals(1, app.poster.channelPosts.size)
    }

    @Test
    fun `a backgrounded app notifies for the room still on screen`() = runTest {
        val app = ForegroundHarness(this)
        app.coordinator.setActiveRoom(SESSION)
        app.background()

        app.postRoom()

        assertEquals(1, app.poster.roomPosts.size)
    }

    @Test
    fun `returning to the foreground suppresses the DM again without re-claiming it`() = runTest {
        val app = ForegroundHarness(this)
        app.coordinator.setActiveContact(NotificationFixtures.CONTACT)
        app.background()
        app.postDirect()
        assertEquals(1, app.poster.directPosts.size)

        app.foreground()
        app.postDirect()

        assertEquals(1, app.poster.directPosts.size)
    }

    @Test
    fun `returning to the foreground suppresses the channel again without re-claiming it`() = runTest {
        val app = ForegroundHarness(this)
        app.coordinator.setActiveChannel(NotificationFixtures.RADIO, CHANNEL_INDEX)
        app.background()
        app.postChannel()
        assertEquals(1, app.poster.channelPosts.size)

        app.foreground()
        app.postChannel()

        assertEquals(1, app.poster.channelPosts.size)
    }

    @Test
    fun `returning to the foreground suppresses the room again without re-claiming it`() = runTest {
        val app = ForegroundHarness(this)
        app.coordinator.setActiveRoom(SESSION)
        app.background()
        app.postRoom()
        assertEquals(1, app.poster.roomPosts.size)

        app.foreground()
        app.postRoom()

        assertEquals(1, app.poster.roomPosts.size)
    }

    @Test
    fun `a foreground app suppresses the thread on screen`() = runTest {
        val app = ForegroundHarness(this)
        app.foreground()
        app.coordinator.setActiveContact(NotificationFixtures.CONTACT)

        app.postDirect()

        assertTrue(app.poster.directPosts.isEmpty())
    }

    @Test
    fun `a backgrounded app still honors the mute gate`() = runTest {
        val app = ForegroundHarness(this)
        app.coordinator.setActiveContact(NotificationFixtures.CONTACT)
        app.background()

        app.coordinator.postDirectMessage(
            NotificationFixtures.contact(isMuted = true),
            "hi",
            NotificationFixtures.CONTACT,
            hasSelfMention = false,
        )

        assertTrue(app.poster.directPosts.isEmpty())
    }

    /**
     * The coordinator behind the real lifecycle seam, wired exactly as the Android container wires it, so
     * the test exercises the edge the platform observer drives rather than the flag directly.
     */
    private class ForegroundHarness(scope: CoroutineScope) {
        val poster = RecordingPoster()
        val coordinator = NotificationCoordinator(
            poster = poster,
            activeConversation = ActiveConversationState(),
            scope = scope,
            now = { NotificationFixtures.NOW_SECONDS },
        )
        private val lifecycle = AppLifecycleController(
            scope = scope,
            onActivate = {},
            onForeground = {},
            onBackground = {},
            onForegroundChanged = { coordinator.isForeground = it },
        )

        fun foreground() = lifecycle.onEnterForeground()

        fun background() = lifecycle.onEnterBackground()

        suspend fun postDirect() = coordinator.postDirectMessage(
            NotificationFixtures.contact(),
            "hi",
            NotificationFixtures.CONTACT,
            hasSelfMention = false,
        )

        suspend fun postChannel() = coordinator.postChannelMessage(
            ChannelMessageNotice(
                channel = NotificationFixtures.channel(index = CHANNEL_INDEX),
                channelIndex = CHANNEL_INDEX,
                radioID = NotificationFixtures.RADIO,
                senderNodeName = "Bob",
                text = "status?",
                messageID = NotificationFixtures.CONTACT,
                hasSelfMention = false,
            ),
        )

        suspend fun postRoom() = coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = SESSION,
                senderName = "Bob",
                text = "status?",
                messageID = NotificationFixtures.CONTACT,
                notificationLevel = NotificationLevel.ALL,
            ),
        )
    }

    private companion object {
        const val CHANNEL_INDEX = 2
        val SESSION: Uuid = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD")
    }
}
