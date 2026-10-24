// PortedFrom: MC1/Views/Settings/NodeConfig/NodeConfigExportView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/NodeConfig/NodeConfigImportView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/ConfigExportImportSection.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.app.nodeconfig

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.ui.SharedUiScaffold
import com.meshcoreone.android.core.ui.sharedTouchTarget
import java.math.BigDecimal

@Composable
fun NodeConfigSection(ready: Boolean, onExport: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(S.configExportSectionTitle), Modifier.semantics { heading() })
        TextButton(onExport, Modifier.fillMaxWidth().sharedTouchTarget(), enabled = ready) {
            Text(stringResource(S.configExportExport))
        }
        TextButton(onImport, Modifier.fillMaxWidth().sharedTouchTarget(), enabled = ready) {
            Text(stringResource(S.configImportImportConfig))
        }
        Text(stringResource(S.configExportSectionFooter), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun NodeConfigExportScreen(holder: NodeConfigStateHolder, documents: NodeConfigDocuments, onDone: () -> Unit) {
    val state by holder.exportState.collectAsState()
    ConfigFrame(S.configExportTitle, onDone) {
        ConfigToggle(S.configExportSelectAll, state.sections.allSelected, !state.busy, {
            holder.selectExport(if (it) state.sections.selectAll() else state.sections.deselectAll())
        })
        SectionChoices(state.sections, !state.busy, holder::selectExport)
        Button({ holder.export(documents) }, Modifier.fillMaxWidth().sharedTouchTarget(),
            enabled = !state.busy && state.sections.anySectionSelected) {
            Text(stringResource(if (state.sections.allSelected) S.configExportExportFull else S.configExportExportSelected))
        }
        if (state.busy) CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun NodeConfigImportScreen(holder: NodeConfigStateHolder, documents: NodeConfigDocuments, onDone: () -> Unit) {
    val state by holder.importState.collectAsState()
    val resources = LocalContext.current.resources
    DisposableEffect(holder) { onDispose(holder::handleDismissal) }
    ConfigFrame(S.configImportTitle, onDone) {
        val config = state.config
        if (config == null) {
            Button({ holder.pick(documents) }, Modifier.fillMaxWidth().sharedTouchTarget(), enabled = !state.parsing) {
                Text(stringResource(S.configImportSelectFile))
            }
            if (state.parsing) CircularProgressIndicator()
        } else {
            SectionChoices(state.sections, !state.busy && !state.confirmation, holder::selectImport, config, state.current)
            Text(stringResource(S.configImportProximityWarning), style = MaterialTheme.typography.bodySmall)
            if (state.applying) {
                val progress = state.progress
                LinearProgressIndicator(
                    progress = { if (progress == null) 0f else progress.current.toFloat() / progress.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                progress?.let { Text(it.description) }
                TextButton(holder::cancelImport, Modifier.sharedTouchTarget()) { Text(stringResource(L.commonCancel)) }
            } else if (state.complete) {
                Text(stringResource(S.configImportImportSuccess))
            } else {
                Button(holder::prepareConfirmation, Modifier.fillMaxWidth().sharedTouchTarget(),
                    enabled = !state.planning && state.sections.anySectionSelected) {
                    Text(stringResource(applyLabel(state)))
                }
                if (state.planning) CircularProgressIndicator()
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (state.confirmation) {
        val name = state.deviceName.ifEmpty { resources.getString(S.configImportThisDevice) }
        val message = when {
            state.overwrite && state.additive -> S.configImportConfirmMessageMixed(resources, name)
            state.overwrite -> S.configImportConfirmMessageOverwrite(resources, name)
            state.additive -> S.configImportConfirmMessageAdd(resources, name)
            else -> S.configImportConfirmMessage(resources, name)
        }
        AlertDialog(onDismissRequest = holder::dismissConfirmation,
            title = { Text(stringResource(when {
                state.overwrite && !state.additive -> S.configImportConfirmTitleOverwrite
                !state.overwrite && state.additive -> S.configImportConfirmTitleAdd
                else -> S.configImportConfirmTitle
            })) },
            text = { Text(message) },
            confirmButton = { TextButton(holder::apply) { Text(stringResource(applyLabel(state))) } },
            dismissButton = { TextButton(holder::dismissConfirmation) { Text(stringResource(L.commonCancel)) } })
    }
}

private fun applyLabel(state: NodeConfigImportState) = when {
    state.overwrite && !state.additive -> S.configImportApplyButtonOverwrite
    !state.overwrite && state.additive -> S.configImportApplyButtonAdd
    else -> S.configImportApplyButton
}

@Composable
private fun ConfigFrame(title: Int, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SharedUiScaffold(topBar = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            TextButton(onDone, Modifier.sharedTouchTarget()) { Text(stringResource(L.commonDone)) }
        }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ConfigToggle(title: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit,
                         description: String? = null, details: @Composable ColumnScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().sharedTouchTarget().toggleable(
        value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange,
    )
        .padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
            details()
        }
        Switch(checked, null, enabled = enabled)
    }
}

@Composable
private fun SectionChoices(
    sections: ConfigSections, enabled: Boolean, change: (ConfigSections) -> Unit,
    config: MeshCoreNodeConfig? = null, current: MeshCoreNodeConfig? = null,
) {
    val r = LocalContext.current.resources
    if (config == null || config.name != null || config.privateKey != null || config.publicKey != null) {
        ConfigToggle(S.configExportNodeIdentity, sections.nodeIdentity, enabled, { change(sections.copy(nodeIdentity = it)) },
            if (config == null) stringResource(S.configExportNodeIdentityDescription) else null) {
            config?.name?.let { Diff(current?.name, it) }
            if (config?.privateKey != null) Text(stringResource(S.configImportPrivateKeyWarning))
        }
    }
    if (config == null || config.radioSettings != null) {
        ConfigToggle(S.configExportRadioSettings, sections.radioSettings, enabled, { change(sections.copy(radioSettings = it)) },
            if (config == null) stringResource(S.configExportRadioSettingsDescription) else null) {
            config?.radioSettings?.let { radio ->
                Diff(current?.radioSettings?.let(::radioText), radioText(radio))
                if (current?.radioSettings != null && current.radioSettings != radio) Text(stringResource(S.configImportRadioWarning))
            }
        }
    }
    if (config == null || config.positionSettings != null) {
        ConfigToggle(S.configExportPositionSettings, sections.positionSettings, enabled, { change(sections.copy(positionSettings = it)) },
            if (config == null) stringResource(S.configExportPositionSettingsDescription) else null) {
            config?.positionSettings?.let { Diff(current?.positionSettings?.let { "${it.latitude}, ${it.longitude}" }, "${it.latitude}, ${it.longitude}") }
        }
    }
    if (config == null || config.otherSettings != null) ConfigToggle(S.configExportOtherSettings, sections.otherSettings,
        enabled, { change(sections.copy(otherSettings = it)) },
        if (config == null) stringResource(S.configExportOtherSettingsDescription) else null)
    if (config == null || config.channels != null) ConfigToggle(S.configExportChannels, sections.channels, enabled,
        { change(sections.copy(channels = it)) }, config?.channels?.let { S.configImportChannelCount(r, it.size) }
            ?: stringResource(S.configExportChannelsDescription))
    if (config == null || config.contacts != null) ConfigToggle(S.configExportContacts, sections.contacts, enabled,
        { change(sections.copy(contacts = it)) }, config?.contacts?.let { S.configImportContactCount(r, it.size) }
            ?: stringResource(S.configExportContactsDescription))
}

@Composable
private fun Diff(current: String?, imported: String) {
    val r = LocalContext.current.resources
    Text(S.configImportCurrent(r, current ?: "\u2014"), style = MaterialTheme.typography.bodySmall)
    Text(S.configImportNew(r, imported), style = MaterialTheme.typography.bodySmall)
}

private fun radioText(radio: MeshCoreNodeConfig.RadioSettings): String {
    fun scaled(value: UInt) = BigDecimal(value.toString()).movePointLeft(3).stripTrailingZeros().toPlainString()
    return "${scaled(radio.frequency)} MHz, BW ${scaled(radio.bandwidth)} kHz, SF ${radio.spreadingFactor}, CR ${radio.codingRate}"
}
