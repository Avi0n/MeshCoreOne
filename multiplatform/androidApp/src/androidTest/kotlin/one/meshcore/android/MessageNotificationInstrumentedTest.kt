package one.meshcore.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.ChannelMessageNotification
import one.meshcore.shared.notification.DirectMessageNotification
import one.meshcore.shared.notification.NotificationChannelKey
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The W2-S5 exit-gate evidence: the Android poster surfaces a real notification without crashing, and the
 * message channels exist. POST_NOTIFICATIONS is granted so the posted notification actually reaches the
 * shade (the poster is a no-op without it, which is itself the pre-grant safety this asserts on the
 * channel-creation path). Mirrors the W1 ConnectionService GrantPermissionRule pattern.
 */
@RunWith(AndroidJUnit4::class)
class MessageNotificationInstrumentedTest {
    @get:Rule
    val postNotifications: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() = manager.cancelAll()

    @Test
    fun channelsAreCreated() {
        MessageNotificationChannels.ensureChannels(context)
        assertTrue(manager.getNotificationChannel(MessageNotificationChannels.MESSAGES_CHANNEL_ID) != null)
        assertTrue(manager.getNotificationChannel(MessageNotificationChannels.MENTIONS_CHANNEL_ID) != null)
    }

    @Test
    fun directMessageNotificationIsPosted() = runBlocking {
        val poster = AndroidNotificationPoster(context)
        val messageID = Uuid.normalized("00000000-0000-0000-0000-000000000001")
        poster.postDirect(
            DirectMessageNotification(
                contactID = Uuid.normalized("00000000-0000-0000-0000-0000000000AA"),
                messageID = messageID,
                title = "Alice",
                body = "hello from the mesh",
                playSound = false,
                channelKey = NotificationChannelKey.MESSAGES,
            ),
        )
        assertTrue("direct notification should reach the shade", awaitPosted(messageID))
    }

    @Test
    fun channelMessageNotificationIsPosted() = runBlocking {
        val poster = AndroidNotificationPoster(context)
        val radioID = Uuid.normalized("00000000-0000-0000-0000-0000000000BB")
        val messageID = Uuid.normalized("00000000-0000-0000-0000-000000000002")
        poster.postChannel(
            ChannelMessageNotification(
                radioID = radioID,
                channelIndex = 2,
                messageID = messageID,
                title = "General",
                body = "Bob: hi all",
                playSound = false,
                channelKey = NotificationChannelKey.MESSAGES,
            ),
        )
        assertTrue("channel notification should reach the shade", awaitPosted(messageID))
        poster.removeDeliveredForChannel(radioID, channelIndex = 2)
        assertTrue("channel notification should be cleared", awaitPosted(messageID, expected = false))
    }

    private suspend fun awaitPosted(messageID: Uuid, expected: Boolean = true): Boolean {
        val settled = withTimeoutOrNull(TIMEOUT_MS) {
            while (isPosted(messageID) != expected) kotlinx.coroutines.delay(POLL_MS)
            true
        }
        return settled == true
    }

    private fun isPosted(messageID: Uuid): Boolean = manager.activeNotifications.any { it.tag == messageID.value }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
