package one.meshcore.shared.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.pathdetail.BlockSenderViewModel
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockSenderOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementServiceReloadsMatchesAndDropsRetiredSelection() {
        val firstService = service(FIRST_KEY_BYTE)
        val replacementService = service(REPLACEMENT_KEY_BYTE)
        var liveService: ContactService? = firstService
        val serviceOwner = mutableStateOf(firstService)
        val viewModel = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { liveService },
                blockedChannelSenderService = { null },
            ),
        )

        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    BlockSenderSheet(
                        senderName = CONTACT_NAME,
                        radioID = RADIO,
                        viewModel = viewModel,
                        serviceOwner = serviceOwner.value,
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            viewModel.state.value.matchingContacts.singleOrNull()?.publicKey?.first() == FIRST_KEY_BYTE.toByte()
        }
        compose.runOnIdle { viewModel.toggleContact(viewModel.state.value.matchingContacts.single().id) }

        compose.runOnIdle {
            liveService = replacementService
            serviceOwner.value = replacementService
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            viewModel.state.value.matchingContacts.singleOrNull()?.publicKey?.first() == REPLACEMENT_KEY_BYTE.toByte()
        }

        assertEquals(REPLACEMENT_KEY_BYTE.toByte(), viewModel.state.value.matchingContacts.single().publicKey.first())
        assertTrue(viewModel.state.value.selectedContactIDs.isEmpty())
    }

    private fun service(keyByte: Int): ContactService {
        val store = InMemoryContactStore()
        runBlocking { store.saveContact(RADIO, contact(keyByte)) }
        return ContactService(NoOpContactSession(), store, now = { 0L })
    }

    private fun contact(keyByte: Int) = ContactFrame(
        publicKey = ByteArray(PUBLIC_KEY_LENGTH) { keyByte.toByte() },
        type = ContactType.CHAT,
        flags = 0,
        outPathLength = FLOOD_PATH,
        outPath = ByteArray(0),
        name = CONTACT_NAME,
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
    )

    private companion object {
        const val CONTACT_NAME = "Alice"
        const val FIRST_KEY_BYTE = 0x11
        const val REPLACEMENT_KEY_BYTE = 0x22
        const val PUBLIC_KEY_LENGTH = 32
        const val FLOOD_PATH = 0xFF
        const val LOAD_TIMEOUT_MS = 5_000L
        val RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000011")
    }
}
