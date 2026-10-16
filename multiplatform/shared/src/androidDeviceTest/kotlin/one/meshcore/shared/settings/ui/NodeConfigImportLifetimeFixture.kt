package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_settings_config_import_error_invalid_file
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.settings_config_import_apply_button_overwrite
import one.meshcore.shared.resources.settings_config_import_cancelled
import one.meshcore.shared.resources.settings_config_import_cancelled_partial
import one.meshcore.shared.resources.settings_config_import_confirm_title_overwrite
import one.meshcore.shared.resources.settings_config_import_current
import one.meshcore.shared.resources.settings_config_import_import_success
import one.meshcore.shared.resources.settings_config_import_new
import one.meshcore.shared.resources.settings_config_import_select_file
import one.meshcore.shared.resources.settings_device_info_no_device_title
import one.meshcore.shared.settings.MeshCoreNodeConfig
import one.meshcore.shared.settings.NodeConfigJson
import one.meshcore.shared.settings.NodeConfigService
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

internal class NodeConfigImportLifetimeFixture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val workspace = NodeConfigImportWorkspace(scope)
    val session = NodeConfigImportLifetimeSession()
    val service = NodeConfigService(session)
    val replacementSession = NodeConfigImportLifetimeSession()
    val replacementService = NodeConfigService(replacementSession)
    val currentService = mutableStateOf(service)
    val currentRadio = mutableStateOf(RADIO)
    val shown = mutableStateOf(true)
    val replacementShown = mutableStateOf(false)
    val mount = mutableStateOf(0)
    val serviceAvailable = mutableStateOf(true)
    var pendingPick: CompletableDeferred<String?>? = null
    var pickJob: Job? = null
    var pickCalls = 0
    var refreshCalls = 0
    val labels = Labels()
    private val json = NodeConfigJson.encode(MeshCoreNodeConfig(name = IMPORTED_NAME))

    fun cancelOwnerScope(compose: ComposeContentTestRule) {
        scope.cancel()
        compose.waitUntil(CLEANUP_TIMEOUT_MS) { checkNotNull(scope.coroutineContext[Job]).isCompleted }
    }

    @Composable
    fun Content() {
        labels.selectFile = stringResource(Res.string.settings_config_import_select_file)
        labels.apply = stringResource(Res.string.settings_config_import_apply_button_overwrite)
        labels.confirmTitle = stringResource(Res.string.settings_config_import_confirm_title_overwrite)
        labels.cancel = stringResource(Res.string.localizable_common_cancel)
        labels.cancelled = stringResource(Res.string.settings_config_import_cancelled)
        labels.cancelledPartial = stringResource(Res.string.settings_config_import_cancelled_partial)
        labels.genericError = stringResource(Res.string.contacts_common_error_occurred)
        labels.invalidFile = stringResource(Res.string.android_settings_config_import_error_invalid_file)
        labels.noDevice = stringResource(Res.string.settings_device_info_no_device_title)
        labels.currentName = formattedStringResource(
            Res.string.settings_config_import_current,
            NodeConfigImportLifetimeSession.CURRENT_NAME,
        )
        labels.success = stringResource(Res.string.settings_config_import_import_success)
        labels.importedName = formattedStringResource(Res.string.settings_config_import_new, IMPORTED_NAME)
        MaterialTheme {
            if (shown.value) {
                key(mount.value) {
                    ImportPane(MOUNT_TAG)
                }
            }
            if (replacementShown.value) {
                ImportPane(REPLACEMENT_TAG)
            }
        }
    }

    @Composable
    private fun ImportPane(tag: String) {
        Box(Modifier.testTag(tag)) {
            NodeConfigImportScreen(
                nodeConfigService = { currentService.value.takeIf { serviceAvailable.value } },
                radioId = { currentRadio.value },
                deviceNodeName = { NodeConfigImportLifetimeSession.CURRENT_NAME },
                pickJsonFile = {
                    pickCalls += 1
                    pickJob = currentCoroutineContext()[Job]
                    val pending = pendingPick
                    if (pending == null) json else pending.await()
                },
                onImportApplied = { refreshCalls += 1 },
                workspace = workspace,
            )
        }
    }

    class Labels {
        lateinit var selectFile: String
        lateinit var apply: String
        lateinit var confirmTitle: String
        lateinit var cancel: String
        lateinit var cancelled: String
        lateinit var importedName: String
        lateinit var cancelledPartial: String
        lateinit var genericError: String
        lateinit var invalidFile: String
        lateinit var noDevice: String
        lateinit var currentName: String
        lateinit var success: String
    }

    companion object {
        const val MOUNT_TAG = "node-config-import-mount"
        const val REPLACEMENT_TAG = "node-config-import-replacement"
        const val IMPORTED_NAME = "Replacement node"
        val RADIO: Uuid = Uuid.normalized("C01DF100-0000-4000-8000-000000000001")
        val REPLACEMENT_RADIO: Uuid = Uuid.normalized("C01DF100-0000-4000-8000-000000000002")
        private const val CLEANUP_TIMEOUT_MS = 5_000L
    }
}
