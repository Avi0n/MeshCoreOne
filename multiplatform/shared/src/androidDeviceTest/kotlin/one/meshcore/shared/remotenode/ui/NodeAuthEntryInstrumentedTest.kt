package one.meshcore.shared.remotenode.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.contact.ui.ContactDetailInputs
import one.meshcore.shared.contact.ui.ContactDetailScreen
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.Uuid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the contact-detail entry point for remote-node auth: tapping "Management" on a repeater's
 * contact detail screen, then "Connect" on its disconnected placeholder, opens [NodeAuthenticationSheet].
 * Connect moved off the Nodes-row long-press menu (trimmed to favorite/block/delete for iOS parity) onto
 * the contact detail screen's Management sheet, so this drives the real [ContactDetailScreen] against an
 * in-memory contact store. The shell sheet host's not-connected placeholder is file-private, so this harness
 * stands in for it with the same "Connect" affordance the real placeholder renders when no admin session
 * is live — the contact-scoped management screen can't be reached against a live radio on a radio-less
 * emulator, the same no-radio limit documented for onboarding.
 */
@RunWith(AndroidJUnit4::class)
class NodeAuthEntryInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun contactFrame() = ContactFrame(
        publicKey = ByteArray(PUBLIC_KEY_LENGTH) { 0xAA.toByte() },
        type = ContactType.REPEATER,
        flags = 0,
        outPathLength = FLOOD_SENTINEL,
        outPath = ByteArray(0),
        name = CONTACT_NAME,
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
    )

    @Composable
    private fun Harness(contactService: ContactService) {
        var managing by remember { mutableStateOf<ContactDto?>(null) }
        var authing by remember { mutableStateOf<ContactDto?>(null) }
        val target = authing
        val managed = managing
        when {
            target != null -> {
                val scope = rememberCoroutineScope()
                val viewModel = remember {
                    NodeAuthViewModel(
                        scope = scope,
                        contact = target,
                        role = RemoteNodeRole.REPEATER,
                        dependencies = NodeAuthViewModel.Dependencies(
                            repeaterAdminService = { null },
                            roomServerService = { null },
                            passwordStore = { null },
                            contactService = { null },
                            radioId = { null },
                            errorText = { TEST_ERROR },
                        ),
                    )
                }
                NodeAuthenticationSheet(
                    viewModel = viewModel,
                    contact = target,
                    role = RemoteNodeRole.REPEATER,
                    onSuccess = { authing = null },
                    onDismiss = { authing = null },
                )
            }
            managed != null -> {
                // Stands in for ShellSheetHost's private NotConnectedPlaceholder: the Management sheet's
                // disconnected state offers this same Connect action into the auth sheet.
                Column {
                    Text(NOT_CONNECTED_LABEL)
                    TextButton(onClick = { authing = managed }) { Text(CONNECT_LABEL) }
                }
            }
            else -> ContactDetailScreen(
                inputs = ContactDetailInputs(
                    contactId = CONTACT_ID.value,
                    contactService = { contactService },
                    radioId = { RADIO_ID },
                    onBack = {},
                    onSendMessage = {},
                    onEditPath = {},
                    onShare = {},
                    onManagement = { contact -> managing = contact },
                ),
            )
        }
    }

    @Test
    fun connectRowActionOpensAuthSheet() {
        val store = InMemoryContactStore(newId = { CONTACT_ID })
        runBlocking { store.saveContact(RADIO_ID, contactFrame()) }
        val contactService = ContactService(NoOpContactSession(), store, now = { 0L })

        composeRule.setContent {
            TimestampFormatting {
                MaterialTheme { Harness(contactService) }
            }
        }

        // The Management action sits below the fold on the contact-detail scroll column.
        composeRule.onNodeWithText(MANAGEMENT_LABEL).performScrollTo().performClick()
        composeRule.onNodeWithText(CONNECT_LABEL).performClick()

        // The Authentication section header proves the sheet opened (it has no row-menu counterpart).
        composeRule.onNodeWithText(AUTH_SECTION).assertExists()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), SCREENSHOT_NAME).outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)
        }
    }

    private companion object {
        const val PUBLIC_KEY_LENGTH = 32
        const val FLOOD_SENTINEL = 0xFF
        const val CONTACT_NAME = "Test Repeater"
        const val MANAGEMENT_LABEL = "Management"
        const val NOT_CONNECTED_LABEL = "Not connected"
        const val CONNECT_LABEL = "Connect"
        const val AUTH_SECTION = "Authentication"
        const val TEST_ERROR = "Test error"
        const val SCREENSHOT_NAME = "node-auth-sheet.png"
        const val PNG_QUALITY = 100
        val CONTACT_ID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val RADIO_ID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-0000000000FF")
    }
}
