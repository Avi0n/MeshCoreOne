// PortedFrom: MC1/Views/Settings/Sections/WiFiSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/WiFiEditSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.container.AppSettingsFeature
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.ui.WiFiAddressValidation
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import kotlinx.coroutines.*

@Composable
internal fun AppWifiSettings(feature: AppSettingsFeature, device: DeviceDTO?, onDone: () -> Unit) {
    val connection = device?.connectionMethods?.filterIsInstance<ConnectionMethod.WiFi>()?.firstOrNull()
    var host by remember(connection) { mutableStateOf(connection?.host.orEmpty()) }
    var port by remember(connection) { mutableStateOf(connection?.port?.toString() ?: "5000") }
    var busy by remember { mutableStateOf(false) }
    val valid = WiFiAddressValidation.isValidHost(host) && WiFiAddressValidation.isValidPort(port)
    val changed = WiFiAddressValidation.normalizedHost(host) != connection?.host || port != connection?.port?.toString()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(S.wifiHeader), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(host, { host = it }, label = { Text(stringResource(S.wifiAddress)) }, enabled = !busy, singleLine = true)
        OutlinedTextField(port, { port = it }, label = { Text(stringResource(S.wifiPort)) }, enabled = !busy, singleLine = true)
        Text(stringResource(S.wifiEditFooter))
        Button(enabled = valid && changed && !busy, onClick = {
            busy = true
            val address = WiFiAddressValidation.normalizedHost(host)
            val number = port.toUShort()
            val entry = feature.container.navigation.state.value.activeStack.last().id
            feature.container.uiScope.launch {
                try {
                    feature.container.appState.disconnect(RuntimeDisconnectReason.WIFI_ADDRESS_CHANGE)
                    feature.container.appState.connectViaWiFi(address, number, forceFullSync = true)
                    if (feature.container.navigation.state.value.activeStack.last().id == entry) onDone()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) { feature.report("Wi-Fi connection update failed", problem) }
                finally { busy = false }
            }
        }) { Text(stringResource(if (busy) S.wifiEditReconnecting else S.wifiEditSaveChanges)) }
        TextButton(enabled = !busy, onClick = onDone) { Text(stringResource(L.commonDone)) }
    }
}
