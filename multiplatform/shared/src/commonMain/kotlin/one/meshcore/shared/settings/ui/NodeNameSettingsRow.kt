package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import one.meshcore.shared.contact.utf8BytePrefix
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_save
import one.meshcore.shared.resources.settings_node_alert_edit_name_title
import one.meshcore.shared.resources.settings_node_name
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubInset
import org.jetbrains.compose.resources.stringResource

/**
 * The node name, editable when a write seam is supplied. The name is clamped to the firmware's usable
 * name budget as it is typed, on a character boundary, so a multi-byte name is never cut mid-character.
 */
@Composable
internal fun NodeNameRow(
    nodeName: String,
    onSetNodeName: (suspend (String) -> Unit)?,
    onMaxRetriesExceeded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val retryAlert = rememberRetryAlertState()
    val settingsErrorText = rememberSettingsErrorText()
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(nodeName) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val writer = onSetNodeName
    if (writer == null) {
        PreferenceHubValueRow(
            stringResource(Res.string.settings_node_name),
            nodeName,
            icon = AppIcons.AccountCircle,
        )
        return
    }

    ListItem(
        headlineContent = { Text(stringResource(Res.string.settings_node_name)) },
        supportingContent = {
            Text(nodeName, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = {
            Icon(imageVector = AppIcons.AccountCircle, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !isSaving) {
            draft = nodeName
            errorMessage = null
            editing = true
        },
    )
    SettingsWriteFeedback(retryAlert, errorMessage) { errorMessage = null }

    if (!editing) return
    FullScreenOverlay(
        onDismiss = { if (!isSaving) editing = false },
        title = stringResource(Res.string.settings_node_alert_edit_name_title),
    ) { innerPadding ->
        Column(modifier = Modifier.preferenceHubInset(innerPadding)) {
            PreferenceHubSection {
                AppOutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.utf8BytePrefix(MAX_USABLE_NAME_BYTES) },
                    label = { Text(stringResource(Res.string.settings_node_name)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(PreferenceHubCardPadding),
                    singleLine = true,
                    enabled = !isSaving,
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val name = draft.trim()
                    if (name.isEmpty()) return@Button
                    editing = false
                    launchNodeNameSave(
                        scope,
                        NodeNameSave(
                            name = name,
                            inFlight = isSaving,
                            onSave = writer,
                            onSaving = { isSaving = it },
                            onError = { errorMessage = it },
                            settingsErrorText = settingsErrorText,
                            retryAlert = retryAlert,
                            onMaxRetriesExceeded = onMaxRetriesExceeded,
                        ),
                    )
                },
                enabled = draft.isNotBlank() && !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PreferenceHubHorizontalPadding),
            ) {
                if (isSaving) {
                    FilledButtonProgress()
                }
                Text(stringResource(Res.string.localizable_common_save))
            }
        }
    }
}
