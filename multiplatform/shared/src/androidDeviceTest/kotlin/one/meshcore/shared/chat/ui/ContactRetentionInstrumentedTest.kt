package one.meshcore.shared.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.navigateToNewChat
import one.meshcore.shared.navigation.reconcileSelectedChatRoute
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomContactStore
import one.meshcore.shared.persistence.RoomConversationListStore
import one.meshcore.shared.persistence.conversationDaos
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.ui.refreshedPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Android Room IDs drive the real list root's reconciliation even when its visible snapshot stays empty. */
@RunWith(AndroidJUnit4::class)
class ContactRetentionInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "contact-retention-${Uuid.random().value}.db"
    private val radioID = Uuid.random()
    private lateinit var database: MeshDatabase
    private lateinit var contactStore: RoomContactStore
    private lateinit var listStore: RoomConversationListStore

    @Before
    fun setUp() {
        database = meshDatabase(context, Dispatchers.IO, databaseName)
        contactStore = RoomContactStore(database)
        listStore = RoomConversationListStore(radioID, database.conversationDaos())
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun existingEmptyDirectRemainsUntilItsContactIsDeleted() = verifyEmptyDirect(newChat = false)

    @Test
    fun unseenNewDirectClosesWhenItsContactIsDeleted() = verifyEmptyDirect(newChat = true)

    private fun verifyEmptyDirect(newChat: Boolean) {
        val contactID = runBlocking { contactStore.saveContact(radioID, frame("Empty direct")) }
        val blockedID = runBlocking {
            val id = contactStore.saveContact(radioID, frame("Blocked"))
            contactStore.saveContact(requireNotNull(contactStore.fetchContact(id)).copy(isBlocked = true))
            id
        }
        val repeaterID = runBlocking { contactStore.saveContact(radioID, frame("Repeater", ContactType.REPEATER)) }
        val foreignRadio = Uuid.random()
        val foreignID = runBlocking { contactStore.saveContact(foreignRadio, frame("Other radio")) }
        val expectedIDs = setOf(contactID, blockedID, repeaterID)
        runBlocking {
            assertEquals(expectedIDs, listStore.fetchContactIDs(radioID))
            assertEquals(setOf(foreignID), listStore.fetchContactIDs(foreignRadio))
            assertTrue(listStore.fetchConversationContacts(radioID).isEmpty())
        }
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
        var latestIDs: Set<Uuid>? = null
        val snapshotSizes = mutableListOf<Int>()
        lateinit var navigation: NavigationCoordinator
        compose.setContent {
            TimestampFormatting {
                val scope = rememberCoroutineScope()
                val coordinator = remember {
                    NavigationCoordinator(scope).also {
                        if (newChat) it.navigateToNewChat(contactID.value) else it.navigateToChat(contactID.value)
                    }
                }
                SideEffect { navigation = coordinator }
                MaterialTheme {
                    ConversationListRoot(
                        ConversationListInputs(
                            store = { listStore },
                            contactService = { null },
                            radioId = { radioID },
                            connectionState = connectionState,
                            onConversationTap = {},
                            listInvalidations = invalidations,
                            onSnapshotRefreshed = { conversations, ids ->
                                snapshotSizes += conversations.size
                                latestIDs = ids
                                coordinator.reconcileSelectedChatRoute(ids) { it.refreshedPayload(conversations) }
                            },
                        ),
                    )
                }
            }
        }
        compose.waitUntil(LOAD_TIMEOUT_MS) { latestIDs == expectedIDs && invalidations.subscriptionCount.value == 1 }
        compose.runOnIdle { assertEquals(ChatRoute.Direct(contactID.value), navigation.chatsSelectedRoute.value) }

        runBlocking { contactStore.deleteContact(contactID) }
        compose.runOnIdle { assertTrue(invalidations.tryEmit(Unit)) }
        compose.waitUntil(LOAD_TIMEOUT_MS) {
            latestIDs == setOf(blockedID, repeaterID) && navigation.chatsSelectedRoute.value == null
        }
        compose.runOnIdle {
            assertTrue(snapshotSizes.size >= 2)
            assertTrue(snapshotSizes.all { it == 0 })
        }
    }

    private fun frame(name: String, type: ContactType = ContactType.CHAT) = ContactFrame(
        publicKey = ByteArray(PUBLIC_KEY_LENGTH).also {
            Uuid.random().value.encodeToByteArray().copyInto(it, endIndex = PUBLIC_KEY_LENGTH)
        },
        type = type,
        flags = 0,
        outPathLength = 0,
        outPath = byteArrayOf(),
        name = name,
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
    )

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
        const val PUBLIC_KEY_LENGTH = 32
    }
}
