package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RepeaterRegionEntry
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_delete
import one.meshcore.shared.resources.localizable_common_try_again
import one.meshcore.shared.resources.remotenodes_remote_nodes_cancel
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_reboot
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_reboot_confirm_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_reboot_message
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_add_child
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_add_region
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_allow_flood
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_default_scope
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_save_to_device
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_refresh
import one.meshcore.shared.ui.preferenceHubScroll
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteSettingsCommandsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ready = mutableStateOf(false)

    @After
    fun cleanup() {
        scope.cancel()
    }

    @Test
    fun refreshAndRetryFollowReadinessWithoutDroppingTheFields() {
        var reloads = 0
        lateinit var refresh: String
        lateinit var retry: String
        compose.setContent {
            MaterialTheme {
                refresh = stringResource(Res.string.remotenodes_remote_nodes_status_refresh)
                retry = stringResource(Res.string.localizable_common_try_again)
                SettingsDisclosure(expanded = true, onExpandedChange = {
                }, isRadioReady = ready.value, title = "Section", isLoading = false, onReload = {
                    reloads++
                }) {
                    SettingsDisclosureBody(
                        isRadioReady = ready.value,
                        isLoading = false,
                        loaded = false,
                        sectionError = true,
                        onReload = { reloads++ },
                    ) {}
                }
            }
        }
        compose.waitForIdle()
        val refreshButton = compose.onNodeWithContentDescription(refresh).assertIsNotEnabled()
        val retryButton = compose.onNodeWithText(retry).assertIsNotEnabled()
        refreshButton.performTouchInput { click() }
        retryButton.performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(0, reloads)
            ready.value = true
        }
        refreshButton.assertIsEnabled().performClick()
        retryButton.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, reloads)
            ready.value = false
        }
        refreshButton.assertIsNotEnabled()
        retryButton.assertIsNotEnabled()
    }

    @Test
    fun alreadyOpenRebootDialogDisablesConfirmButKeepsDismissUsable() {
        ready.value = true
        var confirms = 0
        var dismissals = 0
        lateinit var confirm: String
        lateinit var cancel: String
        compose.setContent {
            MaterialTheme {
                confirm = stringResource(Res.string.remotenodes_remote_nodes_settings_reboot)
                cancel = stringResource(Res.string.remotenodes_remote_nodes_cancel)
                RemoteRebootConfirmDialog(
                    isRadioReady = ready.value,
                    title = Res.string.remotenodes_remote_nodes_settings_reboot_confirm_title,
                    message = Res.string.remotenodes_remote_nodes_settings_reboot_message,
                    onConfirm = { confirms++ },
                    onDismiss = { dismissals++ },
                )
            }
        }
        compose.waitForIdle()
        val button = compose.onNodeWithText(confirm).assertIsEnabled()
        compose.runOnIdle { ready.value = false }
        button.assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText(cancel).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, confirms)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun regionCommandsDisableWhileLocalChildDraftRemainsUsable() {
        val model = nodeModel()
        lateinit var add: String
        lateinit var save: String
        lateinit var flood: String
        lateinit var delete: String
        lateinit var child: String
        compose.setContent {
            MaterialTheme {
                add = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_add_region)
                save = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_save_to_device)
                flood = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_allow_flood)
                delete = stringResource(Res.string.localizable_common_delete)
                child = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_add_child)
                Column(Modifier.preferenceHubScroll()) {
                    NodeRegionsSection(isRadioReady = ready.value, state = regions(), viewModel = model)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(flood).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription(delete).performScrollTo().assertIsNotEnabled().performTouchInput {
            click()
        }
        compose.onNodeWithText(child).performScrollTo().assertIsEnabled()
        compose.onNode(hasText(add) and hasClickAction()).performScrollTo().assertIsNotEnabled().performTouchInput {
            click()
        }
        compose.onNodeWithText(save).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertNull(model.state.value.applyBanner)
            ready.value = true
        }
        compose.onNode(hasText(add) and hasClickAction()).assertIsEnabled()
        compose.onNodeWithText(save).assertIsEnabled()
        compose.onNodeWithText(flood).performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription(delete).assertIsEnabled()
    }

    @Test
    fun openDefaultScopeMenuStopsAcceptingSelectionsWhenReadinessIsLost() {
        ready.value = true
        val model = nodeModel()
        lateinit var label: String
        compose.setContent {
            MaterialTheme {
                label = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_default_scope)
                Column(Modifier.preferenceHubScroll()) {
                    NodeRegionsSection(isRadioReady = ready.value, state = regions(), viewModel = model)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(label).performScrollTo().performClick()
        val option = compose.onNode(hasContentDescription("North") and hasClickAction()).assertIsEnabled()
        compose.runOnIdle { ready.value = false }
        option.assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertNull(model.state.value.applyBanner) }
    }

    private fun nodeModel() = NodeSettingsViewModel(
        scope,
        Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
        NodeSettingsViewModel.Dependencies { null },
    )

    private fun regions() = NodeSettingsState(
        regionsExpanded = true,
        regionsLoaded = true,
        regions = listOf(RepeaterRegionEntry("North", null, 0, true, false)),
        newRegionName = "South",
        supportsRegionDefaultScope = true,
        defaultScopeLoaded = true,
        hasUnsavedRegionChanges = true,
    )
}
