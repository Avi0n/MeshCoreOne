package one.meshcore.android

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.ServiceContainerIdentity
import one.meshcore.shared.connection.ServiceContainerRuntime
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.ConversationKey
import one.meshcore.shared.notification.consumeQuickReplyDraft
import one.meshcore.shared.persistence.ContactEntity
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Quick replies start at [NotificationActionReceiver]. The receiver reads the remote-input text and
 * calls [AppContainer.handleNotificationQuickReply]. A direct reply that the session accepts clears
 * unread only after that send returns. A direct reply that cannot send parks a draft and posts the
 * failure. A channel reply that cannot send also parks a draft; iOS `handleChannelQuickReply` posts
 * the failure and does not save a draft. A service graph whose radio id is a different partition
 * does not send.
 *
 * For the receiver's `onReceive`, the process container is an [AppContainer] on its own database.
 * A Ready graph therefore does not run against the installed app's database. The session records
 * sends and does not open a radio.
 */
@RunWith(AndroidJUnit4::class)
class NotificationQuickReplyReceiverFixtureTest {
    @get:Rule
    val postNotifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application: MeshCoreApplication
        get() = instrumentation.targetContext.applicationContext as MeshCoreApplication

    @Before
    fun setUp() {
        assertEquals(DEBUG_PACKAGE, application.packageName)
        live = application.container
        refuseLiveRadio()
        if (fixture == null) {
            openFixture()
        }
        install(graph = null, state = ConnectionState.Disconnected)
    }

    @After
    fun tearDown() {
        if (fixture != null) install(graph = null, state = ConnectionState.Disconnected)
        clearFailureNotifications()
        val installed = live
        if (installed != null) assertTrue(application.container === installed)
    }

    @Test
    fun aBlankReplyDoesNotParkADraft() {
        val key = ConversationKey.direct(Uuid.random())
        whileFixtureInstalled { deliver(key, text = null) }
        settle()
        assertNull(draft(key))
        assertFalse(failurePosted(key))
    }

    @Test
    fun aWhitespaceReplyDoesNotParkADraft() {
        val key = ConversationKey.direct(Uuid.random())
        whileFixtureInstalled { deliver(key, text = WHITESPACE_REPLY) }
        settle()
        assertNull(draft(key))
        assertFalse(failurePosted(key))
    }

    @Test
    fun aRoomReplyIsDropped() {
        val key = ConversationKey.room(Uuid.random())
        whileFixtureInstalled { deliver(key, REPLY_TEXT) }
        settle()
        assertNull(draft(key))
        assertFalse(failurePosted(key))
    }

    @Test
    fun aMissingContactReplyIsDropped() {
        val key = ConversationKey.direct(Uuid.random())
        whileFixtureInstalled { deliver(key, REPLY_TEXT) }
        settle()
        assertNull(draft(key))
        assertFalse(failurePosted(key))
    }

    @Test
    fun aDisconnectedDirectReplyParksADraftAndLeavesUnread() {
        val contactId = Uuid.random()
        insertContact(contactId, RADIO)
        val key = ConversationKey.direct(contactId)
        try {
            whileFixtureInstalled { deliver(key, REPLY_TEXT) }
            assertTrue(await { draft(key) == REPLY_TEXT })
            assertEquals(UNREAD_COUNT, unread(contactId))
            val posted = awaitNotification(key)
            assertEquals(DIRECT_FAILURE_ACTIONS, posted.actions?.size ?: 0)
        } finally {
            deleteContact(contactId)
        }
    }

    @Test
    fun aChannelReplyThatCannotSendParksADraft() {
        val key = ConversationKey.channel(RADIO, CHANNEL_INDEX)
        whileFixtureInstalled { deliver(key, REPLY_TEXT) }
        assertTrue("channel failure parks a draft", await { draft(key) == REPLY_TEXT })
        val posted = awaitNotification(key)
        val actions = posted.actions
        assertTrue(actions == null || actions.isEmpty())
    }

    @Test
    fun aDirectReplyClearsUnreadOnlyAfterTheRadioAccepts() {
        val release = CompletableDeferred<Unit>()
        val session = RecordingReplySession(release)
        val contactId = Uuid.random()
        insertContact(contactId, RADIO)
        install(graph(session, RADIO), ConnectionState.Ready(FIXTURE_HANDLE))
        val key = ConversationKey.direct(contactId)
        try {
            // goAsync keeps the ordered broadcast open until sendMessage returns, so the
            // result receiver cannot be the signal that the receiver has started.
            whileFixtureInstalled {
                val delivered = sendReply(key, REPLY_TEXT)
                assertTrue(await { session.directSendEntered.isCompleted })
                assertEquals(UNREAD_COUNT, unread(contactId))
                assertEquals(listOf(REPLY_TEXT), session.directSends)
                assertTrue(session.channelSends.isEmpty())
                release.complete(Unit)
                assertTrue(await { unread(contactId) == 0 })
                assertTrue(
                    "notification receiver did not finish",
                    delivered.await(WAIT_MS, TimeUnit.MILLISECONDS),
                )
            }
            assertNull(draft(key))
            assertFalse(failurePosted(key))
        } finally {
            if (!release.isCompleted) release.complete(Unit)
            deleteContact(contactId)
        }
    }

    @Test
    fun aRadioChangeBeforeDeliveryDoesNotSend() {
        val session = RecordingReplySession()
        val contactId = Uuid.random()
        insertContact(contactId, RADIO)
        install(graph(session, OTHER_RADIO), ConnectionState.Ready(FIXTURE_HANDLE))
        val key = ConversationKey.direct(contactId)
        try {
            whileFixtureInstalled { deliver(key, REPLY_TEXT) }
            assertTrue(await { draft(key) == REPLY_TEXT })
            assertEquals(UNREAD_COUNT, unread(contactId))
            assertTrue(session.directSends.isEmpty())
            assertTrue(session.channelSends.isEmpty())
            assertFalse(session.directSendEntered.isCompleted)
        } finally {
            deleteContact(contactId)
        }
    }

    private fun openFixture() {
        val directory = File(application.cacheDir, FIXTURE_DIRECTORY).apply { mkdirs() }
        fixtureRoot = directory
        instrumentation.runOnMainSync {
            fixture = AppContainer(IsolatedProcessContext(application, directory))
        }
        runBlocking { requireFixture().awaitInitialOfflineData() }
    }

    private fun graph(session: RecordingReplySession, radioID: Uuid): ServiceContainer = ServiceContainer(
        session = session,
        database = requireFixture().database,
        identity = ServiceContainerIdentity(
            radioId = radioID,
            selfPublicKey = SELF_PUBLIC_KEY,
            selfNodeName = FIXTURE_NODE_NAME,
        ),
        runtime = ServiceContainerRuntime(
            scope = requireFixture().appScope,
            nodePasswordStore = InMemoryNodePasswordStore(),
            nowEpochSeconds = { FIXED_NOW_SECONDS },
        ),
    )

    private fun install(graph: ServiceContainer?, state: ConnectionState) {
        val manager = requireFixture().connectionManager
        servicesField.set(manager, graph)
        @Suppress("UNCHECKED_CAST")
        val flow = stateField.get(manager) as MutableStateFlow<ConnectionState>
        flow.value = state
    }

    private fun liveContainer(): AppContainer = checkNotNull(live)

    private fun refuseLiveRadio() {
        check(liveContainer().connectionManager.services == null) {
            "debug app has a live service graph; refusing to deliver a reply"
        }
    }

    private fun whileFixtureInstalled(block: () -> Unit) {
        refuseLiveRadio()
        val field = MeshCoreApplication::class.java.getDeclaredField("container")
        field.isAccessible = true
        val previous = field.get(application)
        check(previous === liveContainer())
        field.set(application, requireFixture())
        try {
            block()
        } finally {
            field.set(application, previous)
        }
    }

    private fun deliver(key: ConversationKey, text: String?) {
        val delivered = sendReply(key, text)
        assertTrue(
            "notification receiver did not run",
            delivered.await(WAIT_MS, TimeUnit.MILLISECONDS),
        )
    }

    private fun sendReply(key: ConversationKey, text: String?): CountDownLatch {
        failureKeys += key
        val delivered = CountDownLatch(1)
        val finish = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                delivered.countDown()
            }
        }
        application.sendOrderedBroadcast(
            replyIntent(key, text),
            null,
            finish,
            null,
            Activity.RESULT_OK,
            null,
            null,
        )
        return delivered
    }

    private fun replyIntent(key: ConversationKey, text: String?): Intent {
        val intent = Intent(application, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            setPackage(application.packageName)
            putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_KEY, key.value)
        }
        if (text != null) {
            val results = Bundle()
            results.putCharSequence(NotificationActionReceiver.KEY_REPLY_TEXT, text)
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY_TEXT).build()),
                intent,
                results,
            )
        }
        return intent
    }

    private fun insertContact(id: Uuid, radioID: Uuid) = runBlocking {
        requireFixture().database.contactDao().upsert(contactRow(id, radioID))
    }

    private fun deleteContact(id: Uuid) = runBlocking {
        requireFixture().database.contactDao().deleteById(id)
    }

    private fun unread(id: Uuid): Int = runBlocking {
        requireFixture().database.contactDao().getById(id)?.unreadCount ?: error("missing contact $id")
    }

    private fun draft(key: ConversationKey): String? = runBlocking {
        requireFixture().notificationCoordinator.consumeQuickReplyDraft(key)
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(SETTLE_MS)
        instrumentation.waitForIdleSync()
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    private fun awaitNotification(key: ConversationKey): Notification {
        assertTrue(await { failurePosted(key) })
        return failureNotification(key) ?: error("failure notification missing")
    }

    private fun failurePosted(key: ConversationKey): Boolean = failureNotification(key) != null

    private fun failureNotification(key: ConversationKey): Notification? {
        val manager = application.getSystemService(NotificationManager::class.java)
        return manager.activeNotifications.firstOrNull { status ->
            status.id == NotificationActionReceiver.QUICK_REPLY_FAILED_NOTIFICATION_ID &&
                status.tag == NotificationActionReceiver.quickReplyFailedTag(key)
        }?.notification
    }

    private fun clearFailureNotifications() {
        val manager = NotificationManagerCompat.from(application)
        failureKeys.forEach { key ->
            manager.cancel(
                NotificationActionReceiver.quickReplyFailedTag(key),
                NotificationActionReceiver.QUICK_REPLY_FAILED_NOTIFICATION_ID,
            )
        }
        failureKeys.clear()
    }

    private fun contactRow(id: Uuid, radioID: Uuid) = ContactEntity(
        id = id,
        radioID = radioID,
        publicKey = CONTACT_PUBLIC_KEY,
        name = CONTACT_NAME,
        typeRawValue = ContactType.CHAT.raw,
        flags = 0,
        outPathLength = FLOOD_OUT_PATH_LENGTH,
        outPath = byteArrayOf(),
        lastAdvertTimestamp = 0L,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0L,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = UNREAD_COUNT,
    )

    private class IsolatedProcessContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = directory("files")

        override fun getCacheDir(): File = directory("cache")

        override fun getNoBackupFilesDir(): File = directory("nobackup")

        override fun getDataDir(): File = root

        override fun getDatabasePath(name: String): File = File(directory("databases"), name)

        private fun directory(name: String): File = File(root, name).apply { mkdirs() }
    }

    private companion object {
        const val DEBUG_PACKAGE = "io.pocketmesh.app.debug"
        const val FIXTURE_DIRECTORY = "quick-reply-fixture"
        const val FIXTURE_HANDLE = "fixture-handle"
        const val FIXTURE_NODE_NAME = "Fixture"
        const val CONTACT_NAME = "Ada"
        const val REPLY_TEXT = "On my way"
        const val WHITESPACE_REPLY = "   "
        const val UNREAD_COUNT = 2
        const val CHANNEL_INDEX = 4
        const val DIRECT_FAILURE_ACTIONS = 2
        const val FLOOD_OUT_PATH_LENGTH = -1
        const val WAIT_MS = 8_000L
        const val POLL_MS = 50L
        const val SETTLE_MS = 400L
        const val PUBLIC_KEY_LENGTH = 32
        const val SELF_KEY_FILL = 0x3C
        const val CONTACT_KEY_FILL = 0x21
        const val FIXED_NOW_SECONDS = 1_700_000_000.0

        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAA11")
        val OTHER_RADIO: Uuid = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBB22")
        val SELF_PUBLIC_KEY = ByteArray(PUBLIC_KEY_LENGTH) { SELF_KEY_FILL.toByte() }
        val CONTACT_PUBLIC_KEY = ByteArray(PUBLIC_KEY_LENGTH) { CONTACT_KEY_FILL.toByte() }

        val servicesField = ConnectionManager::class.java.getDeclaredField("services").apply { isAccessible = true }
        val stateField = ConnectionManager::class.java.getDeclaredField("mutableState").apply { isAccessible = true }

        var fixture: AppContainer? = null
        var fixtureRoot: File? = null
        var live: AppContainer? = null
        val failureKeys = mutableSetOf<ConversationKey>()

        fun requireFixture(): AppContainer = checkNotNull(fixture)

        @AfterClass
        @JvmStatic
        fun closeFixture() {
            fixture?.appScope?.cancel()
            fixture?.database?.close()
            fixtureRoot?.deleteRecursively()
            fixture = null
            fixtureRoot = null
        }
    }
}
