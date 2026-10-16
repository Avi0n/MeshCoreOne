package one.meshcore.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MentionPickerOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementServiceReloadsTheOpenPicker() {
        val serviceOwner = mutableStateOf(service(FIRST_NICKNAME, FIRST_SECOND_NICKNAME, 0x11))
        val sheet = ShellSheet.MentionPicker(MENTION_NAME, emptyList(), isSelfMention = false)

        compose.setContent {
            MaterialTheme {
                MentionPickerSheet(
                    sheet = sheet,
                    contactService = serviceOwner.value,
                    radioId = RADIO_ID,
                    localNodeName = LOCAL_NODE_NAME,
                    onDismiss = {},
                    onOpenContact = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithText(FIRST_NICKNAME).fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle {
            serviceOwner.value = service(REPLACEMENT_NICKNAME, REPLACEMENT_SECOND_NICKNAME, 0x22)
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithText(REPLACEMENT_NICKNAME).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(FIRST_NICKNAME).assertDoesNotExist()
        compose.onNodeWithText(REPLACEMENT_NICKNAME).assertExists()
    }

    private fun service(firstNickname: String, secondNickname: String, keyByte: Int): ContactService {
        val store = InMemoryContactStore()
        runBlocking {
            saveContact(store, firstNickname, keyByte)
            saveContact(store, secondNickname, keyByte + 1)
        }
        return ContactService(NoOpContactSession(), store, now = { 0L })
    }

    private suspend fun saveContact(store: InMemoryContactStore, nickname: String, keyByte: Int) {
        val key = ByteArray(PUBLIC_KEY_LENGTH) { keyByte.toByte() }
        val id = store.saveContact(RADIO_ID, contact(key))
        store.saveContact(requireNotNull(store.fetchContact(id)).copy(nickname = nickname))
    }

    private fun contact(publicKey: ByteArray) = ContactFrame(
        publicKey = publicKey,
        type = ContactType.CHAT,
        flags = 0,
        outPathLength = FLOOD_PATH,
        outPath = byteArrayOf(),
        name = MENTION_NAME,
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
    )

    private companion object {
        const val MENTION_NAME = "Responder"
        const val LOCAL_NODE_NAME = "Local"
        const val FIRST_NICKNAME = "Old Alpha"
        const val FIRST_SECOND_NICKNAME = "Old Bravo"
        const val REPLACEMENT_NICKNAME = "New Alpha"
        const val REPLACEMENT_SECOND_NICKNAME = "New Bravo"
        const val PUBLIC_KEY_LENGTH = 32
        const val FLOOD_PATH = 0xFF
        const val LOAD_TIMEOUT_MS = 5_000L
        val RADIO_ID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    }
}
