// PortedFrom: MC1/Views/Settings/SystemPairingSetupSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.app.state.AppState
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as Settings

/** Mounted above both first-run onboarding and the main shell, including Settings radio setup. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SystemPairingSetupHost(state: AppState) {
    val ui by state.connectionUI.state.collectAsStateWithLifecycle()
    val prompt = ui.pendingSystemPairingSetup ?: return
    key(prompt.id) {
        ModalBottomSheet(
            onDismissRequest = {
                if (state.connectionUI.pendingSystemPairingSetup?.id == prompt.id) {
                    state.cancelSystemPairingSetup()
                    state.handleSystemPairingSetupSheetDismissed()
                }
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            val plural = prompt.accessories.size > 1
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
                    .testTag("system-pairing-setup"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (prompt.accessories.size == 1) {
                        stringResource(
                            com.meshcoreone.android.core.l10n.R.string.l10n_app_settings_deviceselection_setuptitle,
                            prompt.accessories.single().name,
                        )
                    } else stringResource(Settings.deviceSelectionSetupTitleGeneric),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(if (plural) Settings.deviceSelectionSetupFooterPlural else Settings.deviceSelectionSetupFooter))
                if (plural) prompt.accessories.forEach { Text(it.name) }
                Text(stringResource(Settings.deviceSelectionSetupConversations))
                Button(
                    onClick = {
                        if (state.connectionUI.pendingSystemPairingSetup?.id == prompt.id) {
                            state.confirmSystemPairingSetup()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("system-pairing-forget"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(if (plural) Settings.deviceSelectionForgetTheseDevices else Settings.deviceSelectionForgetThisDevice))
                }
                TextButton(
                    onClick = {
                        if (state.connectionUI.pendingSystemPairingSetup?.id == prompt.id) {
                            state.cancelSystemPairingSetup()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("system-pairing-cancel"),
                ) { Text(stringResource(L.commonCancel)) }
            }
        }
    }
}
