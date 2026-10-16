package one.meshcore.shared.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareContactOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementServiceReloadsContactsWithoutDroppingSearch() {
        val firstService = service(FIRST_NAME, FIRST_KEY_BYTE)
        val replacementService = service(REPLACEMENT_NAME, REPLACEMENT_KEY_BYTE)
        var liveService: ContactService? = firstService
        val serviceOwner = mutableStateOf(firstService)

        compose.setContent {
            MaterialTheme {
                ShareContactPickerSheet(
                    loadContacts = { liveService?.getContacts(RADIO).orEmpty() },
                    serviceOwner = serviceOwner.value,
                    radioId = RADIO,
                    onInsert = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription(FIRST_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performTextInput(SEARCH_TEXT)

        compose.runOnIdle {
            liveService = replacementService
            serviceOwner.value = replacementService
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription(REPLACEMENT_NAME).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNode(hasSetTextAction()).assertTextEquals(SEARCH_TEXT)
    }

    private fun service(name: String, keyByte: Int): ContactService {
        val store = InMemoryContactStore()
        runBlocking { store.saveContact(RADIO, contact(name, keyByte)) }
        return ContactService(NoOpContactSession(), store, now = { 0L })
    }

    private fun contact(name: String, keyByte: Int) = ContactFrame(
        publicKey = ByteArray(PUBLIC_KEY_LENGTH) { keyByte.toByte() },
        type = ContactType.CHAT,
        flags = 0,
        outPathLength = FLOOD_PATH,
        outPath = ByteArray(0),
        name = name,
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
    )

    private companion object {
        const val FIRST_NAME = "Alice One"
        const val REPLACEMENT_NAME = "Alice Two"
        const val SEARCH_TEXT = "Alice"
        const val FIRST_KEY_BYTE = 0x11
        const val REPLACEMENT_KEY_BYTE = 0x22
        const val PUBLIC_KEY_LENGTH = 32
        const val FLOOD_PATH = 0xFF
        const val LOAD_TIMEOUT_MS = 5_000L
        val RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000011")
    }
}
