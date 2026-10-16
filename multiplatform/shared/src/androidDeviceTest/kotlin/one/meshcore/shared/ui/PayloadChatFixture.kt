package one.meshcore.shared.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatCoordinatorRegistry
import one.meshcore.shared.chat.ChatDraftEditors
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.chat.ui.ChatThreadEnvironment
import one.meshcore.shared.chat.ui.ChatThreadGraphInputs
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.RoomChatStore
import one.meshcore.shared.persistence.RoomContactStore
import one.meshcore.shared.persistence.RoomConversationListStore
import one.meshcore.shared.persistence.RxLogCorrelator
import one.meshcore.shared.persistence.chatDaos
import one.meshcore.shared.persistence.conversationDaos
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore

/** Local Room and drafts only; sends and session services are absent. */
internal class PayloadChatFixture(private val context: Context, scope: CoroutineScope) {
    private val databaseName = "chat-payload-${Uuid.random().value}.db"
    private val database = meshDatabase(context, Dispatchers.IO, databaseName)
    val radioID = Uuid.random()
    private val contacts = RoomContactStore(database)
    var contact = runBlocking {
        val id = contacts.saveContact(
            radioID,
            ContactFrame(
                publicKey = ByteArray(PUBLIC_KEY_LENGTH), type = ContactType.CHAT, flags = 0,
                outPathLength = 0, outPath = byteArrayOf(), name = ORIGINAL,
                lastAdvertTimestamp = 0, latitude = 0.0, longitude = 0.0, lastModified = 0,
            ),
        )
        requireNotNull(contacts.fetchContact(id)).copy(lastMessageDate = LAST_MESSAGE_DATE).also {
            contacts.saveContact(it)
        }
    }
        private set
    val listStore = RoomConversationListStore(radioID, database.conversationDaos())
    private val threadStore = RoomChatStore(radioID, database.chatDaos(), RxLogCorrelator(database.rxLogEntryDao()))
    private val registry = ChatCoordinatorRegistry(threadStore, scope)
    private val drafts = runBlocking { DraftStore.create(InMemoryPreferencesStore()) }
    private val draftEditors = ChatDraftEditors(drafts, scope)

    private val environment = ChatThreadEnvironment(
        ChatThreadGraphInputs(
            registry = { registry },
            store = { threadStore },
            sendQueue = { null },
            messageService = { null },
            draftEditors = { draftEditors },
            teardownScope = { scope },
            currentContact = { contact },
            unreadCount = { 0 },
            localNodeName = { null },
            incomingEvents = { emptyFlow() },
            statusEvents = { emptyFlow() },
        ),
    )
    var missNextLookup = false
    var successfulLookups = 0
        private set
    val binder = ChatThreadBinder(
        resolveDestination = {
            if (missNextLookup) {
                missNextLookup = false
                null
            } else {
                val live = contacts.fetchContact(contact.id)
                live?.let {
                    successfulLookups += 1
                    ChatDestination(ChatConversationID.dm(radioID, it.id), it.name, it)
                }
            }
        },
        environment = { environment },
    )

    fun rename(name: String) = runBlocking {
        contact = contact.copy(name = name)
        contacts.saveContact(contact)
    }

    fun close() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    companion object {
        const val ORIGINAL = "Payload contact"
        private const val PUBLIC_KEY_LENGTH = 32
        private const val LAST_MESSAGE_DATE = 1.0
    }
}
