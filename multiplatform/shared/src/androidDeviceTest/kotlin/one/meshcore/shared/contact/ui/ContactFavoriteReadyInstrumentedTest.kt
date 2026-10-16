package one.meshcore.shared.contact.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactDetailLogic
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_favorite
import one.meshcore.shared.resources.localizable_accessibility_requires_radio_connection
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContactFavoriteReadyInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val ready = mutableStateOf(false)
    private val contact = mutableStateOf(fixture())
    private lateinit var label: String
    private lateinit var hint: String
    private var toggles = 0

    @Test
    fun disconnectedFavoritesRetainOnAndOffWithoutInvokingTheAction() {
        showActions()
        val row = compose.onNodeWithText(label).assertIsDisplayed().assertIsNotEnabled().assertIsOn()
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, hint))
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(1)
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        row.performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, toggles) }
        captureRow(DISABLED_SCREENSHOT)

        compose.runOnIdle { contact.value = contact.value.copy(isFavorite = false) }
        row.assertIsNotEnabled().assertIsOff().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, toggles) }
    }

    @Test
    fun readinessChangesEnableAndDisableTheSameFavoriteRow() {
        showActions()
        val row = compose.onNodeWithText(label).assertIsNotEnabled().assertIsOn()
        compose.runOnIdle { ready.value = true }
        row.assertIsEnabled().assertIsOn()
        assertNull(row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        captureRow(READY_SCREENSHOT)
        row.performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(1, toggles)
            ready.value = false
        }
        row.assertIsNotEnabled().assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, hint))
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, toggles) }
    }

    private fun showActions() {
        compose.setContent {
            MaterialTheme {
                label = stringResource(Res.string.contacts_detail_favorite)
                hint = stringResource(Res.string.localizable_accessibility_requires_radio_connection)
                ContactDetailUtilitySection(
                    contact = contact.value,
                    actionFlags = ContactDetailLogic.actionFlags(ContactType.CHAT, false, false),
                    enablement = ContactDetailLogic.actionEnablement(
                        isRadioReady = ready.value,
                        busy = ContactDetailLogic.ActionBusyState(),
                    ),
                    radioHint = if (ready.value) null else hint,
                    onJoinRoom = null,
                    onShowTelemetry = null,
                    onShowSavedHistory = null,
                    onManagement = null,
                    onToggleFavorite = {
                        toggles++
                        contact.value = contact.value.copy(isFavorite = !contact.value.isFavorite)
                    },
                    onRequestBlock = {},
                    onShare = {},
                    shareAdvertBusy = false,
                    shareAdvertSuccess = false,
                    shareAdvertError = null,
                    onShareViaAdvert = {},
                    onPing = null,
                    isPinging = false,
                    pingResult = null,
                )
            }
        }
    }

    private fun captureRow(name: String) {
        val bitmap = compose.onNodeWithText(label).captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private companion object {
        const val DISABLED_SCREENSHOT = "favorite-disabled.png"
        const val READY_SCREENSHOT = "favorite-ready.png"

        fun fixture() = ContactDto(
            id = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC"),
            radioID = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
            publicKey = ByteArray(32),
            name = "Alice",
            typeRawValue = 1,
            flags = 1,
            outPathLength = -1,
            outPath = byteArrayOf(),
            lastAdvertTimestamp = 0,
            latitude = 0.0,
            longitude = 0.0,
            lastModified = 0,
            isBlocked = false,
            isMuted = false,
            isFavorite = true,
            unreadCount = 0,
        )
    }
}
