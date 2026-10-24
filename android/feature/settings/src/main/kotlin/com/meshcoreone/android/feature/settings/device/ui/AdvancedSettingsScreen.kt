// PortedFrom: MC1/Views/Settings/AdvancedSettingsView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/NodesSettingsSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/StaleNodeCleanupSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/TelemetrySettingsSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/DefaultFloodScopeSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/DangerZoneSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/DiagnosticsSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/DeviceActionsSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/DeviceIdentitySection.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.AutoAddMode
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.AsyncActionLabel
import com.meshcoreone.android.core.ui.radioActionEnabled
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.settings.device.AdvancedSettingsStateHolder
import com.meshcoreone.android.feature.settings.device.BatteryCurveStateHolder
import com.meshcoreone.android.feature.settings.device.ContactsSettingsStateHolder
import com.meshcoreone.android.feature.settings.device.DangerZoneStateHolder
import com.meshcoreone.android.feature.settings.device.DefaultFloodScopeStateHolder
import com.meshcoreone.android.feature.settings.device.DeviceActionsStateHolder
import com.meshcoreone.android.feature.settings.device.DiagnosticsStateHolder
import com.meshcoreone.android.feature.settings.device.DirectMessagesStateHolder
import com.meshcoreone.android.feature.settings.device.MaxHopsOptions
import com.meshcoreone.android.feature.settings.device.RegenerateIdentityStateHolder
import com.meshcoreone.android.feature.settings.device.RemoveOutcome
import com.meshcoreone.android.feature.settings.device.SettingsExternalDestination
import com.meshcoreone.android.feature.settings.device.SettingsNavigator
import com.meshcoreone.android.feature.settings.device.StaleCleanupFooter
import com.meshcoreone.android.feature.settings.device.StaleNodeCleanupStateHolder
import com.meshcoreone.android.feature.settings.device.TelemetrySettingsStateHolder
import kotlinx.coroutines.launch

class AdvancedSettingsHolders(
    val page: AdvancedSettingsStateHolder,
    val floodScope: DefaultFloodScopeStateHolder,
    val contacts: ContactsSettingsStateHolder,
    val stale: StaleNodeCleanupStateHolder,
    val telemetry: TelemetrySettingsStateHolder,
    val directMessages: DirectMessagesStateHolder,
    val battery: BatteryCurveStateHolder,
    val actions: DeviceActionsStateHolder,
    val regenerate: RegenerateIdentityStateHolder,
    val dangerZone: DangerZoneStateHolder,
    val diagnostics: DiagnosticsStateHolder,
)

/**
 * Settings > My Device > Advanced. [onDismissPage] pops the page after a forget or factory reset.
 * [nodeConfigurationSection] supplies the WP-318 section and owns its connection guards and navigation.
 */
@Composable
fun AdvancedSettingsScreen(
    holders: AdvancedSettingsHolders, navigator: SettingsNavigator, supportsDefaultFloodScope: Boolean,
    onShareFile: (String) -> Unit, onDismissPage: () -> Unit, onDone: (() -> Unit)?,
    nodeConfigurationSection: (@Composable () -> Unit)? = null,
) {
    HolderLifecycle(
        { with(holders) { page.start(); floodScope.start(); contacts.start(); stale.start(); telemetry.start(); directMessages.start(); battery.start() } },
        { with(holders) { page.stop(); floodScope.stop(); contacts.stop(); stale.stop(); telemetry.stop(); directMessages.stop(); battery.stop(); dangerZone.cancelPendingRemoval() } },
    )
    SettingsScreenFrame(res(AppSettingsStrings.advancedSettingsTitle), onDone) {
        OfflineMapsSection(navigator)
        if (supportsDefaultFloodScope) FloodScopeSection(holders.floodScope, navigator)
        ContactsSection(holders.contacts)
        StaleCleanupSection(holders.stale)
        TelemetrySection(holders.telemetry, navigator)
        DirectMessagesSection(holders.directMessages)
        BatteryCurveSection(holders.battery)
        nodeConfigurationSection?.invoke()
        DeviceActionsSection(holders.actions)
        IdentitySection(holders.regenerate, navigator)
        DangerZoneSection(holders.dangerZone, onDismissPage)
        DiagnosticsSection(holders.diagnostics, onShareFile)
    }
}

/** Link to the shared offline-map settings (core:maps, WP-312); this module only opens the destination. */
@Composable
private fun OfflineMapsSection(navigator: SettingsNavigator) {
    SettingsSection(res(AppSettingsStrings.mapsOfflineHeader)) {
        ActionRow(res(AppSettingsStrings.offlineMapsTitle), true, { navigator.open(SettingsExternalDestination.OfflineMapSettings) })
    }
}

@Composable
private fun FloodScopeSection(holder: DefaultFloodScopeStateHolder, navigator: SettingsNavigator) {
    val state by holder.state.collectAsState()
    SettingsSection(res(AppSettingsStrings.defaultFloodScopeHeader), listOf(res(AppSettingsStrings.defaultFloodScopeFooter))) {
        val rows = listOf<String?>(null) + state.sortedKnownRegions
        for (region in rows) {
            val title = region?.let { UiText.Verbatim(it) } ?: res(AppSettingsStrings.defaultFloodScopeDisabled)
            val selected = state.currentScope == region
            ActionRow(title, state.enabled, { holder.select(region) }, detail = if (selected) UiText.Verbatim("✓") else null)
            RowDivider()
        }
        state.discoveryMessageId?.let { Text(stringResource(it), Modifier.padding(vertical = 8.dp)) }
        ActionRow(
            if (state.isDiscovering) UiText.Resource(com.meshcoreone.android.core.l10n.R.string.l10n_app_chats_chats_channelinfo_region_discovering)
            else UiText.Resource(com.meshcoreone.android.core.l10n.R.string.l10n_app_chats_chats_channelinfo_region_discover),
            !state.isDiscovering, holder::discover,
        )
        ActionRow(UiText.Resource(com.meshcoreone.android.core.l10n.R.string.l10n_app_chats_chats_channelinfo_region_manageregions), true,
            { navigator.open(SettingsExternalDestination.RegionManagement) })
    }
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
    RetryAlertDialog(holder.retry)
}

@Composable
private fun ContactsSection(holder: ContactsSettingsStateHolder) {
    val state by holder.state.collectAsState()
    val enabled = radioActionEnabled(state.connectionState) && !state.isApplying
    val modeName = { mode: AutoAddMode ->
        res(when (mode) {
            AutoAddMode.MANUAL -> AppSettingsStrings.nodesAutoAddModeManual
            AutoAddMode.SELECTED_TYPES -> AppSettingsStrings.nodesAutoAddModeSelectedTypes
            AutoAddMode.ALL -> AppSettingsStrings.nodesAutoAddModeAll
        })
    }
    val resources = LocalContext.current.resources
    SettingsSection(res(AppSettingsStrings.nodesHeader), state.footerIds.map { res(it) }) {
        DropdownRow(res(AppSettingsStrings.nodesAutoAddMode), modeName(state.autoAddMode), state.availableModes.map { modeName(it) to it }, enabled, holder::onModeSelected)
        if (state.showsTypeToggles) {
            SwitchRow(res(AppSettingsStrings.nodesAutoAddContacts), state.autoAddContacts, enabled, holder::onContactsToggled)
            SwitchRow(res(AppSettingsStrings.nodesAutoAddRepeaters), state.autoAddRepeaters, enabled, holder::onRepeatersToggled)
            SwitchRow(res(AppSettingsStrings.nodesAutoAddRoomServers), state.autoAddRoomServers, enabled, holder::onRoomServersToggled)
        }
        if (state.showsMaxHops) {
            val label = { hops: Int?, id: Int? -> if (id != null) res(id) else UiText.Verbatim(AppSettingsStrings.nodesMaxHopsHops(resources, hops ?: 0)) }
            val options = MaxHopsOptions.map { label(it.hops, it.labelId) to it.value }
            val current = MaxHopsOptions.firstOrNull { it.value == state.autoAddMaxHops }
            DropdownRow(res(AppSettingsStrings.nodesMaxHops), current?.let { label(it.hops, it.labelId) } ?: UiText.Verbatim(""), options, enabled, holder::onMaxHopsSelected)
        }
        if (state.supportsAutoAddConfig) {
            SwitchRow(res(AppSettingsStrings.nodesOverwriteOldest), state.overwriteOldest, enabled, holder::onOverwriteOldestToggled,
                description = res(AppSettingsStrings.nodesOverwriteOldestDescription))
        }
        TextButton(holder::apply, Modifier.fillMaxWidth().sharedTouchTarget(), enabled = state.canApply) {
            AsyncActionLabel(state.isApplying, state.showSuccess, res(AppSettingsStrings.advancedRadioApply), res(AppSettingsStrings.advancedRadioApply)) {
                Text(stringResource(AppSettingsStrings.advancedRadioApply))
            }
        }
    }
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
    RetryAlertDialog(holder.retry)
}

@Composable
private fun StaleCleanupSection(holder: StaleNodeCleanupStateHolder) {
    val state by holder.state.collectAsState()
    val resources = LocalContext.current.resources
    val footer = when (state.footer) {
        StaleCleanupFooter.DISABLED -> UiText.Resource(AppSettingsStrings.nodesStaleCleanupFooterDisabled)
        StaleCleanupFooter.SELECT_THRESHOLD -> UiText.Resource(AppSettingsStrings.nodesStaleCleanupFooterSelect)
        StaleCleanupFooter.ENABLED -> UiText.Verbatim(AppSettingsStrings.nodesStaleCleanupFooterEnabled(resources, state.thresholdDays))
        StaleCleanupFooter.DISCONNECTED -> UiText.Resource(AppSettingsStrings.nodesStaleCleanupFooterDisconnected)
    }
    SettingsSection(null, listOf(footer)) {
        SwitchRow(res(AppSettingsStrings.nodesStaleCleanupHeader), state.isEnabled, state.controlsEnabled, holder::onEnabledToggled)
        if (state.isEnabled) {
            val label = { days: Int -> if (days == 0) res(AppSettingsStrings.nodesStaleCleanupSelect) else UiText.Verbatim(AppSettingsStrings.nodesStaleCleanupDays(resources, days)) }
            DropdownRow(res(AppSettingsStrings.nodesStaleCleanupThreshold), label(state.thresholdDays),
                StaleNodeCleanupStateHolder.THRESHOLD_CHOICES.map { label(it) to it }, state.controlsEnabled, holder::onThresholdSelected)
            val last = state.lastCleanup
            if (state.showsLastRun && last != null) {
                val relative = DateUtils.getRelativeTimeSpanString(last.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                Text(AppSettingsStrings.nodesStaleCleanupLastRun(resources, relative), Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun TelemetrySection(holder: TelemetrySettingsStateHolder, navigator: SettingsNavigator) {
    val state by holder.state.collectAsState()
    SettingsSection(res(AppSettingsStrings.telemetryHeader), listOf(res(AppSettingsStrings.telemetryFooter))) {
        SwitchRow(res(AppSettingsStrings.telemetryAllowRequests), state.telemetryEnabled, state.controlsEnabled, holder::onTelemetryToggled,
            description = res(AppSettingsStrings.telemetryAllowRequestsDescription))
        if (state.showsDetails) {
            SwitchRow(res(AppSettingsStrings.telemetryIncludeLocation), state.locationEnabled, state.controlsEnabled, holder::onLocationToggled,
                description = res(AppSettingsStrings.telemetryIncludeLocationDescription))
            SwitchRow(res(AppSettingsStrings.telemetryIncludeEnvironment), state.environmentEnabled, state.controlsEnabled, holder::onEnvironmentToggled,
                description = res(AppSettingsStrings.telemetryIncludeEnvironmentDescription))
            SwitchRow(res(AppSettingsStrings.telemetryTrustedOnly), state.filterByTrusted, state.controlsEnabled, holder::onFilterByTrustedToggled,
                description = res(AppSettingsStrings.telemetryTrustedOnlyDescription))
            if (state.showsManageTrusted) {
                ActionRow(res(AppSettingsStrings.telemetryManageTrusted), true, { navigator.open(SettingsExternalDestination.TrustedContacts) })
            }
        }
    }
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
    RetryAlertDialog(holder.retry)
}

@Composable
private fun DirectMessagesSection(holder: DirectMessagesStateHolder) {
    val state by holder.state.collectAsState()
    SettingsSection(res(AppSettingsStrings.directMessagesHeader), listOf(res(AppSettingsStrings.directMessagesFooter))) {
        DropdownRow(res(AppSettingsStrings.directMessagesAcknowledgments), UiText.Verbatim(state.acknowledgments.toString()),
            listOf(UiText.Verbatim("1") to 1, UiText.Verbatim("2") to 2), state.enabled, holder::onAcknowledgmentsSelected)
    }
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
    RetryAlertDialog(holder.retry)
}

@Composable
private fun DeviceActionsSection(holder: DeviceActionsStateHolder) {
    val state by holder.state.collectAsState()
    SettingsSection(res(AppSettingsStrings.deviceActionsHeader)) {
        ActionRow(
            res(if (state.isRebooting) AppSettingsStrings.deviceActionsRebooting else AppSettingsStrings.deviceActionsRebootDevice),
            !state.isRebooting, holder::requestReboot,
        )
    }
    ConfirmDialog(state.showingRebootAlert, res(AppSettingsStrings.deviceActionsAlertRebootTitle), res(AppSettingsStrings.deviceActionsAlertRebootMessage),
        res(AppSettingsStrings.deviceActionsAlertRebootConfirm), holder::confirmReboot, holder::dismissRebootAlert, destructive = false)
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
}

@Composable
private fun IdentitySection(holder: RegenerateIdentityStateHolder, navigator: SettingsNavigator) {
    var showSheet by remember { mutableStateOf(false) }
    SettingsSection(res(AppSettingsStrings.regenerateIdentityHeader)) {
        ActionRow(res(AppSettingsStrings.importKeyImport), true, { navigator.open(SettingsExternalDestination.ImportPrivateKey) })
        RowDivider()
        ActionRow(res(AppSettingsStrings.regenerateIdentityTitle), true, { showSheet = true })
    }
    if (showSheet) RegenerateIdentitySheet(holder) { holder.cancelGeneration(); showSheet = false }
}

@Composable
private fun DangerZoneSection(holder: DangerZoneStateHolder, onDismissPage: () -> Unit) {
    val state by holder.state.collectAsState()
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    SettingsSection(res(AppSettingsStrings.dangerZoneHeader), listOf(res(AppSettingsStrings.dangerZoneFooter))) {
        ActionRow(
            res(when {
                state.isRemovingUnfavorited -> AppSettingsStrings.dangerZoneRemoving
                state.showRemoveSuccess -> AppSettingsStrings.dangerZoneRemoved
                else -> AppSettingsStrings.dangerZoneRemoveUnfavorited
            }),
            !state.isRemovingUnfavorited, { scope.launch { holder.fetchUnfavoritedCount() } }, destructive = true,
        )
        RowDivider()
        ActionRow(res(AppSettingsStrings.dangerZoneForgetDevice), true, holder::requestForget, destructive = true)
        RowDivider()
        ActionRow(res(if (state.isResetting) AppSettingsStrings.dangerZoneResetting else AppSettingsStrings.dangerZoneFactoryReset),
            !state.isResetting, holder::requestReset, destructive = true)
    }
    if (state.showingForgetConfirmation) {
        AlertDialog(
            onDismissRequest = holder::dismissForget,
            title = { Text(stringResource(AppSettingsStrings.dangerZoneDialogForgetTitle)) },
            text = {
                Column {
                    Text(stringResource(AppSettingsStrings.dangerZoneDialogForgetMessage))
                    ForgetOption(AppSettingsStrings.dangerZoneDialogForgetKeepData) { holder.dismissForget(); scope.launch { if (holder.forgetDevice(false)) onDismissPage() } }
                    ForgetOption(AppSettingsStrings.dangerZoneDialogForgetDeleteAll) { holder.dismissForget(); scope.launch { if (holder.forgetDevice(true)) onDismissPage() } }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(holder::dismissForget, Modifier.sharedTouchTarget()) { Text(stringResource(AppLocalizableStrings.commonCancel)) } },
        )
    }
    ConfirmDialog(state.showingResetAlert, res(AppSettingsStrings.dangerZoneAlertResetTitle), res(AppSettingsStrings.dangerZoneAlertResetMessage),
        res(AppSettingsStrings.dangerZoneAlertResetConfirm), { holder.dismissReset(); scope.launch { if (holder.factoryReset()) onDismissPage() } }, holder::dismissReset)
    ConfirmDialog(state.showingRemoveUnfavoritedAlert, res(AppSettingsStrings.dangerZoneAlertRemoveUnfavoritedTitle),
        UiText.Verbatim(AppSettingsStrings.dangerZoneAlertRemoveUnfavoritedMessage(resources, state.unfavoritedCount)),
        res(AppSettingsStrings.dangerZoneAlertRemoveUnfavoritedConfirm), holder::removeUnfavoritedNodes, holder::dismissRemoveAlert)
    if (state.showRemoveResult) {
        val message = when (val result = state.removeResult) {
            RemoveOutcome.NoneFound -> stringResource(AppSettingsStrings.dangerZoneAlertRemoveUnfavoritedNoneFound)
            is RemoveOutcome.Partial -> AppSettingsStrings.dangerZoneAlertRemoveUnfavoritedPartial(resources, result.removed.toInt(), result.total.toInt())
            null -> ""
        }
        AlertDialog(
            onDismissRequest = holder::dismissRemoveResult,
            title = { Text(stringResource(AppSettingsStrings.dangerZoneAlertRemoveUnfavoritedResultTitle)) },
            text = { Text(message) },
            confirmButton = { TextButton(holder::dismissRemoveResult, Modifier.sharedTouchTarget()) { Text(stringResource(AppLocalizableStrings.commonOk)) } },
        )
    }
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
}

@Composable
private fun ForgetOption(textId: Int, onClick: () -> Unit) {
    TextButton(onClick, Modifier.fillMaxWidth().sharedTouchTarget()) {
        Text(stringResource(textId), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DiagnosticsSection(holder: DiagnosticsStateHolder, onShareFile: (String) -> Unit) {
    val state by holder.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(state.exportedFile) {
        state.exportedFile?.let { onShareFile(it); holder.onExportShared() }
    }
    SettingsSection(res(AppSettingsStrings.diagnosticsHeader), listOf(res(AppSettingsStrings.diagnosticsFooter))) {
        ActionRow(res(AppSettingsStrings.diagnosticsExportLogs), !state.isExporting, holder::exportLogs)
        RowDivider()
        ActionRow(res(AppSettingsStrings.diagnosticsClearLogs), true, holder::requestClearLogs, destructive = true)
    }
    ConfirmDialog(state.showingClearLogsAlert, res(AppSettingsStrings.diagnosticsAlertClearTitle), res(AppSettingsStrings.diagnosticsAlertClearMessage),
        res(AppSettingsStrings.diagnosticsAlertClearConfirm), holder::confirmClearLogs, holder::dismissClearLogsAlert)
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
}
