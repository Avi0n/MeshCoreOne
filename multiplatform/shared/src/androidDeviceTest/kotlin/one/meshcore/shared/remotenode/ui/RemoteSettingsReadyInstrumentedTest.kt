package one.meshcore.shared.remotenode.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodeSettingsController
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_accessibility_requires_radio_connection
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_apply_room_settings
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_apply_behavior_settings
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_apply_contact_info
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_apply_identity_settings
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_apply_radio_settings
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_change_password
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_contact_info
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_device_actions
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_send_advert
import one.meshcore.shared.room.RoomSettingsController
import one.meshcore.shared.room.ui.RoomSettingsScreen
import one.meshcore.shared.room.ui.RoomSettingsUiState
import one.meshcore.shared.room.ui.RoomSettingsViewModel
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RemoteSettingsReadyInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ready = mutableStateOf(false)
    private lateinit var advert: String
    private lateinit var actions: String

    @After
    fun cleanup() {
        scope.cancel()
    }

    @Test
    fun nodeScreenFollowsReadinessWhileMounted() {
        val model = NodeSettingsViewModel(
            scope,
            Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
            NodeSettingsViewModel.Dependencies { null },
        )
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    advert = stringResource(Res.string.remotenodes_remote_nodes_settings_send_advert)
                    actions = stringResource(Res.string.remotenodes_remote_nodes_settings_device_actions)
                    NodeSettingsScreen(model, isRadioReady = ready.value)
                }
            }
        }
        compose.onNodeWithText(actions).performScrollTo().performClick()
        assertLiveGate()
    }

    @Test
    fun roomScreenFollowsReadinessWhileMounted() {
        val helper = NodeSettingsController({ _, _ -> "" }, { _, _ -> "" })
        val controller = RoomSettingsController(helper, { _, _ -> "" }, { _, _ -> "" })
        val model = RoomSettingsViewModel(scope, controller)
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    advert = stringResource(Res.string.remotenodes_remote_nodes_settings_send_advert)
                    actions = stringResource(Res.string.remotenodes_remote_nodes_settings_device_actions)
                    RoomSettingsScreen(model, isRadioReady = ready.value)
                }
            }
        }
        compose.onNodeWithText(actions).performScrollTo().performClick()
        assertLiveGate()
    }

    @Test
    fun contactInfoKeepsDraftEditableAndCombinesReadyBusyAndSuccessGates() {
        val fields = mutableStateOf(
            NodeSettingsState(
                contactInfoExpanded = true,
                contactInfoLoaded = true,
                contactInfoModified = true,
                ownerInfo = "Original draft",
            ),
        )
        var applies = 0
        lateinit var apply: String
        lateinit var editor: String
        lateinit var hint: String
        compose.setContent {
            MaterialTheme {
                apply = stringResource(Res.string.remotenodes_remote_nodes_settings_apply_contact_info)
                editor = stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info)
                hint = stringResource(Res.string.localizable_accessibility_requires_radio_connection)
                RemoteContactInfoSection(
                    onExpandedChange = { fields.value = fields.value.copy(contactInfoExpanded = it) },
                    isRadioReady = ready.value,
                    fields = fields.value,
                    onReload = {},
                    onOwnerInfoChange = { fields.value = fields.value.copy(ownerInfo = it) },
                    onApply = { applies++ },
                )
            }
        }
        compose.waitForIdle()
        val button = compose.onNodeWithText(apply).assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, hint))
        button.performTouchInput { click() }
        compose.onNode(hasText(editor) and hasSetTextAction()).assertIsEnabled()
            .performTextReplacement("Retained offline draft")
        compose.runOnIdle {
            assertEquals(0, applies)
            assertEquals("Retained offline draft", fields.value.ownerInfo)
        }
        val file =
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "remote-contact-disabled.png")
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        compose.runOnIdle { ready.value = true }
        button.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, applies)
            fields.value = fields.value.copy(isApplyingContactInfo = true)
        }
        button.assertIsNotEnabled()
        compose.runOnIdle {
            fields.value =
                fields.value.copy(isApplyingContactInfo = false, contactInfoApplySuccess = true)
        }
        button.assertIsNotEnabled()
        compose.runOnIdle { fields.value = fields.value.copy(contactInfoApplySuccess = false, ownerInfoTooLong = true) }
        button.assertIsNotEnabled()
        compose.runOnIdle { fields.value = fields.value.copy(ownerInfoTooLong = false, contactInfoModified = false) }
        button.assertIsNotEnabled()
        compose.runOnIdle { fields.value = fields.value.copy(contactInfoModified = true) }
        button.assertIsEnabled()
        compose.runOnIdle { ready.value = false }
        button.assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, applies) }
    }

    @Test
    fun roomScreenAppliesTheGateToEveryLoadedModifiedSection() {
        val helper = NodeSettingsController({ _, _ -> "" }, { _, _ -> "" })
        val controller = RoomSettingsController(helper, { _, _ -> "" }, { _, _ -> "" })
        val model = RoomSettingsViewModel(scope, controller)
        val modifiedFields = RoomSettingsUiState(
            roomAccessExpanded = true,
            roomAccessLoaded = true,
            roomAccessModified = true,
            guestPassword = "guest",
            allowReadOnly = true,
            identityExpanded = true,
            identityLoaded = true,
            identityModified = true,
            name = "Node",
            radioExpanded = true,
            radioLoaded = true,
            radioModified = true,
            frequencyText = "915.525",
            behaviorExpanded = true,
            behaviorLoaded = true,
            behaviorModified = true,
            contactInfoExpanded = true,
            contactInfoLoaded = true,
            contactInfoModified = true,
            ownerInfo = "Draft",
            securityExpanded = true,
            newPassword = "password",
            confirmPassword = "password",
        )
        lateinit var labels: List<String>
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    labels = listOf(
                        Res.string.remotenodes_remote_nodes_room_settings_apply_room_settings,
                        Res.string.remotenodes_remote_nodes_settings_apply_radio_settings,
                        Res.string.remotenodes_remote_nodes_settings_apply_behavior_settings,
                        Res.string.remotenodes_remote_nodes_settings_apply_identity_settings,
                        Res.string.remotenodes_remote_nodes_settings_apply_contact_info,
                        Res.string.remotenodes_remote_nodes_settings_change_password,
                    ).map { stringResource(it) }
                    RoomSettingsScreen(model, isRadioReady = ready.value)
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { model.settingsState.value = modifiedFields }
        labels.forEach { label ->
            compose.onNodeWithText(label).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        }
        compose.runOnIdle {
            assertNull(model.state.value.applyBanner)
            ready.value = true
        }
        labels.forEach { label -> compose.onNodeWithText(label).performScrollTo().assertIsEnabled() }
        compose.runOnIdle { ready.value = false }
        labels.forEach { label -> compose.onNodeWithText(label).performScrollTo().assertIsNotEnabled() }
        compose.runOnIdle { assertEquals("Draft", model.state.value.ownerInfo) }
    }

    private fun assertLiveGate() {
        compose.waitForIdle()
        val button = compose.onNodeWithText(advert).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { ready.value = true }
        button.assertIsEnabled()
        compose.runOnIdle { ready.value = false }
        button.assertIsNotEnabled()
    }
}
