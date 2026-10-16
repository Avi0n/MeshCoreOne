package one.meshcore.shared.tools.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_path_edit_add_hop
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TracePathOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementServiceReloadsRetainedBuilder() {
        val firstService = service(FIRST_NAME, FIRST_KEY_BYTE)
        val replacementService = service(REPLACEMENT_NAME, REPLACEMENT_KEY_BYTE)
        var serviceProvider by mutableStateOf<() -> ContactService?>({ firstService })
        lateinit var addHopLabel: String

        compose.setContent {
            TimestampFormatting {
                addHopLabel = stringResource(Res.string.contacts_path_edit_add_hop)
                MaterialTheme {
                    TracePathScreen(
                        runTrace = null,
                        deviceLocation = { null },
                        radioId = { RADIO },
                        contactService = serviceProvider,
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithText(addHopLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText(addHopLabel)[0].performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithText(FIRST_NAME).fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle { serviceProvider = { replacementService } }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithText(REPLACEMENT_NAME).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(FIRST_NAME).assertDoesNotExist()
    }

    private fun service(name: String, keyByte: Int): ContactService {
        val store = InMemoryContactStore()
        runBlocking { store.saveContact(RADIO, contact(name, keyByte)) }
        return ContactService(NoOpContactSession(), store, now = { 0L })
    }

    private fun contact(name: String, keyByte: Int) = ContactFrame(
        publicKey = ByteArray(PUBLIC_KEY_LENGTH) { keyByte.toByte() },
        type = ContactType.REPEATER,
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
        const val FIRST_NAME = "Alpha"
        const val REPLACEMENT_NAME = "Bravo"
        const val FIRST_KEY_BYTE = 0x11
        const val REPLACEMENT_KEY_BYTE = 0x22
        const val PUBLIC_KEY_LENGTH = 32
        const val FLOOD_PATH = 0xFF
        const val LOAD_TIMEOUT_MS = 5_000L
        val RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000011")
    }
}
