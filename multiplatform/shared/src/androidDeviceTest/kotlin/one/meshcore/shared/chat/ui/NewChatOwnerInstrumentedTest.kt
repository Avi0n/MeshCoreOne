package one.meshcore.shared.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.NewChatViewModel
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewChatOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementGraphReloadsRetainedPicker() {
        val firstService = service(FIRST_RADIO, "Alice", 0x11)
        val replacementService = service(SECOND_RADIO, "Bob", 0x22)
        var liveService: ContactService? = firstService
        var liveRadio: Uuid? = FIRST_RADIO
        val serviceOwner = mutableStateOf(firstService)
        val radioOwner = mutableStateOf(FIRST_RADIO)
        val viewModel = NewChatViewModel(
            NewChatViewModel.Dependencies(
                contactService = { liveService },
                radioId = { liveRadio },
            ),
        )
        viewModel.setSearchText("bo")

        compose.setContent {
            MaterialTheme {
                NewChatSheet(
                    viewModel = viewModel,
                    serviceOwner = serviceOwner.value,
                    radioId = radioOwner.value,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            viewModel.state.value.contacts.singleOrNull()?.name == "Alice"
        }

        compose.runOnIdle {
            liveService = replacementService
            liveRadio = SECOND_RADIO
            serviceOwner.value = replacementService
            radioOwner.value = SECOND_RADIO
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            viewModel.state.value.contacts.singleOrNull()?.name == "Bob"
        }

        assertEquals("bo", viewModel.state.value.searchText)
    }

    private fun service(radioID: Uuid, name: String, keyByte: Int): ContactService {
        val store = InMemoryContactStore()
        runBlocking { store.saveContact(radioID, contact(name, keyByte)) }
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
        const val PUBLIC_KEY_LENGTH = 32
        const val FLOOD_PATH = 0xFF
        const val LOAD_TIMEOUT_MS = 5_000L
        val FIRST_RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000011")
        val SECOND_RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000022")
    }
}
