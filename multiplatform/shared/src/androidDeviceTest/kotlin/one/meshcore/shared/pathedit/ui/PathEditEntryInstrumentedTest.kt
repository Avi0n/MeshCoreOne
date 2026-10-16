package one.meshcore.shared.pathedit.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
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
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.PathManagementViewModel
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the contact-detail entry point for the path editor: tapping "Edit Path" on a repeater's contact
 * detail screen opens [PathEditingSheet]. Edit Path moved off the Nodes-row long-press menu (trimmed to
 * favorite/block/delete for iOS parity) onto the contact detail screen, so this drives the real
 * [ContactDetailScreen] against an in-memory contact store, then renders the sheet with a disconnected
 * view model — the contact-scoped path controls can't be reached against a live radio on a radio-less
 * emulator, the same no-radio limit documented for onboarding, but the sheet's empty routing state still
 * renders.
 */
@RunWith(AndroidJUnit4::class)
class PathEditEntryInstrumentedTest {
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
        var editing by remember { mutableStateOf<ContactDto?>(null) }
        val current = editing
        if (current == null) {
            ContactDetailScreen(
                inputs = ContactDetailInputs(
                    contactId = CONTACT_ID.value,
                    contactService = { contactService },
                    radioId = { RADIO_ID },
                    onBack = {},
                    onSendMessage = {},
                    onEditPath = { contact -> editing = contact },
                    onShare = {},
                ),
            )
        } else {
            val scope = rememberCoroutineScope()
            val viewModel = remember {
                PathManagementViewModel(
                    scope = scope,
                    recents = RecentHopsStore(InMemoryPreferencesStore()),
                    dependencies = PathManagementViewModel.Dependencies(
                        contactService = { null },
                        radioId = { null },
                        errorText = { TEST_ERROR },
                    ),
                )
            }
            PathEditingSheet(viewModel = viewModel, contact = current, onDismiss = { editing = null })
        }
    }

    @Test
    fun editPathRowActionOpensSheet() {
        val store = InMemoryContactStore(newId = { CONTACT_ID })
        runBlocking { store.saveContact(RADIO_ID, contactFrame()) }
        val contactService = ContactService(NoOpContactSession(), store, now = { 0L })

        composeRule.setContent {
            TimestampFormatting {
                MaterialTheme { Harness(contactService) }
            }
        }

        // The outbound-path section sits below the fold on the contact-detail scroll column.
        composeRule.onNodeWithText(EDIT_PATH_LABEL).performScrollTo().performClick()

        // The sheet's empty routing state proves it opened (title text collides with the row menu item).
        composeRule.onNodeWithText(EMPTY_STATE_TITLE).assertExists()

        // Capture the opened sheet for the slice's screenshot artifact.
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), SCREENSHOT_NAME).outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)
        }
    }

    private companion object {
        const val PUBLIC_KEY_LENGTH = 32
        const val FLOOD_SENTINEL = 0xFF
        const val CONTACT_NAME = "Test Node"
        const val EDIT_PATH_LABEL = "Edit Path"
        const val EMPTY_STATE_TITLE = "No hops yet"
        const val TEST_ERROR = "Test error"
        const val SCREENSHOT_NAME = "path-edit-sheet.png"
        const val PNG_QUALITY = 100
        val CONTACT_ID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val RADIO_ID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-0000000000FF")
    }
}
