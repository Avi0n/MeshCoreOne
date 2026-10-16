package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_advanced_settings_footer
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.preferenceHubEdgeFill
import one.meshcore.shared.ui.preferenceHubEdgeScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Advanced: manual LoRa parameters, Nodes auto-add policy, battery curve, device
 * config export/import, identity import/regenerate, and Danger Zone (iOS `AdvancedSettingsView`).
 */
@Composable
internal fun AdvancedSettingsScreen(
    host: AdvancedSettingsHost,
    onOpenTrustedContacts: (() -> Unit)? = null,
    onOpenConfigExport: (() -> Unit)? = null,
    onOpenConfigImport: (() -> Unit)? = null,
    onOpenRegionManagement: (() -> Unit)? = null,
    onLeaveAfterDestructive: () -> Unit = {},
    /** After the last retry, leave this page. */
    onMaxRetriesExceeded: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snapshot = host.radioSnapshot()
    val nodesAutoAdd = host.nodesAutoAddSnapshot()
    val onApplyNodesAutoAdd = host.applyNodesAutoAdd
    val otherParams = host.deviceOtherParamsSnapshot()
    val onSetTelemetryModes = host.setTelemetryModes
    val onSetMultiAcks = host.setMultiAcks
    val isDeviceConnected = host.isDeviceConnected
    val staleCleanupThresholdDays = host.staleCleanupThresholdDays
    val onStaleCleanupThresholdChange = host.onStaleCleanupThresholdChange
    val lastStaleCleanupSeconds = host.lastStaleCleanupSeconds
    val nowSeconds = host.nowSeconds
    val deviceOcvSnapshot = host.deviceOcvSnapshot
    val onSaveDeviceOcv = host.updateDeviceOcv
    val knownRegions = host.knownRegions
    val defaultFloodScopeName = host.defaultFloodScopeName
    val onApplyDefaultFloodScope = host.applyDefaultFloodScope
    val onDiscoverRegions = host.discoverNearbyRegions
    val regionDiscoverMessage = host.regionDiscoverMessage
    val onReboot = host.rebootDevice
    val onImportPrivateKey = host.onImportPrivateKey
    val onForgetKeepData = host.onForgetKeepData
    val onForgetDeleteData = host.onForgetDeleteData
    val onFactoryReset = host.onFactoryReset
    val unfavoritedCount = host.unfavoritedCount
    val onRemoveUnfavorited = host.onRemoveUnfavorited
    var identitySheet by remember { mutableStateOf<DeviceIdentitySheet?>(null) }

    val openIdentity = identitySheet
    if (openIdentity != null && onImportPrivateKey != null) {
        DeviceIdentitySheetHost(
            sheet = openIdentity,
            onImportPrivateKey = onImportPrivateKey,
            onDismiss = { identitySheet = null },
        )
        return
    }

    if (snapshot == null && nodesAutoAdd == null) {
        SettingsNoDeviceEmpty(modifier.preferenceHubEdgeFill())
        return
    }

    Column(
        modifier.preferenceHubEdgeScroll(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreferenceHubFooter(
            stringResource(Res.string.settings_advanced_settings_footer),
            applyHorizontalPadding = false,
        )

        if (nodesAutoAdd != null) {
            NodesAutoAddSection(
                snapshot = nodesAutoAdd,
                isDeviceConnected = isDeviceConnected,
                onApply = onApplyNodesAutoAdd,
                onMaxRetriesExceeded = onMaxRetriesExceeded,
            )
        }

        StaleNodeCleanupSection(
            thresholdDays = staleCleanupThresholdDays,
            isDeviceConnected = isDeviceConnected,
            onThresholdChange = onStaleCleanupThresholdChange,
            lastRunSeconds = lastStaleCleanupSeconds,
            nowSeconds = nowSeconds,
            modifier = Modifier.padding(top = 24.dp),
        )

        if (otherParams != null) {
            AdvancedOtherParamsSections(
                snapshot = otherParams,
                enabled = isDeviceConnected,
                onSetTelemetryModes = onSetTelemetryModes,
                onSetMultiAcks = onSetMultiAcks,
                onOpenTrustedContacts = onOpenTrustedContacts,
                onMaxRetriesExceeded = onMaxRetriesExceeded,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        if (onSaveDeviceOcv != null) {
            DeviceBatteryCurveSection(
                deviceOcvSnapshot = deviceOcvSnapshot,
                isDeviceConnected = isDeviceConnected,
                onSave = onSaveDeviceOcv,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        AdvancedConfigTransferSection(
            isDeviceConnected = isDeviceConnected,
            onOpenConfigExport = onOpenConfigExport,
            onOpenConfigImport = onOpenConfigImport,
        )

        // Firmware v11 introduced the default-flood-scope opcode; an older radio rejects every apply,
        // so the section is hidden rather than offered, as on iOS.
        if (onOpenRegionManagement != null && snapshot?.supportsDefaultFloodScope == true) {
            DefaultFloodScopeSection(
                knownRegions = knownRegions(),
                currentScopeName = defaultFloodScopeName(),
                enabled = isDeviceConnected,
                onApply = onApplyDefaultFloodScope,
                onDiscover = onDiscoverRegions,
                discoverMessage = regionDiscoverMessage,
                onManageRegions = onOpenRegionManagement,
                onMaxRetriesExceeded = onMaxRetriesExceeded,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        if (onReboot != null) {
            DeviceActionsSection(
                isDeviceConnected = isDeviceConnected,
                onReboot = onReboot,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        if (onImportPrivateKey != null) {
            DeviceIdentityEntrySection(
                isDeviceConnected = isDeviceConnected,
                onImport = { identitySheet = DeviceIdentitySheet.IMPORT },
                onRegenerate = { identitySheet = DeviceIdentitySheet.REGENERATE },
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        DangerZoneSettingsScreen(
            isDeviceConnected = isDeviceConnected,
            onForgetKeepData = onForgetKeepData,
            onForgetDeleteData = onForgetDeleteData,
            onFactoryReset = onFactoryReset,
            onLeaveAfterDestructive = onLeaveAfterDestructive,
            unfavoritedCount = unfavoritedCount,
            onRemoveUnfavorited = onRemoveUnfavorited,
            embedded = true,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
