// AndroidOnly: WP-407 Explicit settings actions over the existing device/contact/region owners.
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
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.datastore.KeyGenerationService
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.onboarding.ui.RegionPicker
import com.meshcoreone.android.feature.settings.device.SettingsExternalDestination
import com.meshcoreone.android.feature.settings.device.SettingsNotConnectedException
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

@Composable
internal fun AppSettingsExternal(feature: AppSettingsFeature, target: SettingsExternalDestination, onDone: () -> Unit) {
    val container = feature.container
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action(); version++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feature.report("Settings action failed", failure) }
            finally { busy = false }
        }
    }
    fun live() = container.sessions.current?.takeIf {
        container.appState.canRunSettingsStartupReads && it.token.radioId == container.appState.currentRadioId
    } ?: throw SettingsNotConnectedException()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onDone) { Text(stringResource(L.commonDone)) }
        when (target) {
            SettingsExternalDestination.RegionPicker -> {
                val region by feature.regions.selection.collectAsState()
                RegionPicker(region, requireNotNull(container.onboarding).region.catalog, feature.regions::set)
            }
            SettingsExternalDestination.LocationPicker -> {
                var latitude by remember { mutableStateOf(container.appState.connectedDevice?.latitude?.toString().orEmpty()) }
                var longitude by remember { mutableStateOf(container.appState.connectedDevice?.longitude?.toString().orEmpty()) }
                val lat = latitude.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
                val lon = longitude.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
                OutlinedTextField(latitude, { latitude = it }, label = { Text(stringResource(S.locationPickerLatitude)) })
                OutlinedTextField(longitude, { longitude = it }, label = { Text(stringResource(S.locationPickerLongitude)) })
                Button(onClick = { run { live().settingsService.setManualLocationVerified(requireNotNull(lat), requireNotNull(lon)); onDone() } },
                    enabled = lat != null && lon != null && !busy) { Text(stringResource(L.commonSave)) }
            }
            SettingsExternalDestination.ShareContactQr -> {
                val device = container.appState.connectedDevice
                if (device == null) Text(stringResource(L.errorConnectionNotConnected))
                else AppChannelQr(ContactService.exportContactURI(device.nodeName, device.publicKey, ContactType.CHAT), onDone)
            }
            SettingsExternalDestination.ImportPrivateKey -> {
                var text by remember { mutableStateOf("") }
                var replacement by remember { mutableStateOf<Pair<Bytes, com.meshcoreone.android.app.container.RadioSessionContainer>?>(null) }
                Text(stringResource(S.importKeySheetExplanation))
                OutlinedTextField(text, { text = it.uppercase(java.util.Locale.ROOT).filter { char -> char in '0'..'9' || char in 'A'..'F' } },
                    label = { Text(stringResource(S.importKeyKeyInputLabel)) }, enabled = !busy)
                Button(enabled = text.isNotBlank() && !busy, onClick = { run {
                    val key = Bytes.fromHex(text.trim())
                    KeyGenerationService.validateExpandedKey(key)
                    replacement = key to live()
                } }) { Text(stringResource(S.importKeyImport)) }
                replacement?.let { (key, session) ->
                    AlertDialog(onDismissRequest = { replacement = null },
                        title = { Text(stringResource(S.regenerateIdentityAlertReplaceTitle)) },
                        text = { Text(stringResource(S.regenerateIdentityAlertReplaceMessage)) },
                        dismissButton = { TextButton(onClick = { replacement = null }) { Text(stringResource(L.commonCancel)) } },
                        confirmButton = { TextButton(onClick = {
                            replacement = null
                            run {
                                if (live() !== session) throw SettingsNotConnectedException()
                                session.settingsService.importPrivateKey(key)
                                session.settingsService.refreshDeviceInfo()
                                text = ""
                                onDone()
                            }
                        }) { Text(stringResource(S.regenerateIdentityAlertReplaceConfirm)) } })
                }
            }
            SettingsExternalDestination.RegionManagement -> {
                var name by remember { mutableStateOf("") }
                var known by remember { mutableStateOf(emptyList<String>()) }
                LaunchedEffect(version) { known = container.appState.connectedDevice?.knownRegions.orEmpty() }
                known.forEach { region ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(region)
                        TextButton(enabled = !busy, onClick = { run {
                            val current = live()
                            if (container.appState.connectedDevice?.defaultFloodScopeName == region) {
                                current.settingsService.setDefaultFloodScopeVerified(null)
                                if (live() !== current) throw SettingsNotConnectedException()
                            }
                            container.processStore.removeDeviceKnownRegion(current.token.radioId, region)
                            val updated = requireNotNull(container.processStore.fetchDevice(current.token.radioId))
                            if (live() !== current) throw SettingsNotConnectedException()
                            val cleared = updated.takeIf { it.defaultFloodScopeName != region }
                                ?: updated.copy(defaultFloodScopeName = null)
                            if (cleared != updated) container.processStore.saveDevice(cleared)
                            container.deviceEditor.updateDevice(cleared)
                        } }) {
                            Text(stringResource(L.commonDelete))
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(S.radioPresetLocation)) })
                Button(enabled = name.isNotBlank() && !busy, onClick = { run {
                    val current = live()
                    container.processStore.addDeviceKnownRegion(current.token.radioId, name.trim())
                    val updated = requireNotNull(container.processStore.fetchDevice(current.token.radioId))
                    if (live() !== current) throw SettingsNotConnectedException()
                    container.deviceEditor.updateDevice(updated)
                    name = ""
                } }) { Text(stringResource(L.commonSave)) }
            }
            SettingsExternalDestination.BlockedContacts, SettingsExternalDestination.TrustedContacts -> {
                var contacts by remember { mutableStateOf<List<ContactDTO>?>(null) }
                LaunchedEffect(version, container.appState.currentRadioId) {
                    try {
                        contacts = container.appState.currentRadioId?.let { container.processStore.fetchContacts(it) }.orEmpty()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { feature.report("Could not load contact preferences", failure); contacts = emptyList() }
                }
                if (contacts == null) CircularProgressIndicator()
                else contacts.orEmpty().filter {
                    target == SettingsExternalDestination.TrustedContacts || it.isBlocked
                }.forEach { contact ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(contact.displayName)
                        if (target == SettingsExternalDestination.TrustedContacts) {
                            Switch(ContactService.hasTelemetryPermissions(contact.flags), enabled = !busy,
                                onCheckedChange = { granted -> run {
                                    val current = live()
                                    if (current.token.radioId != contact.radioId) throw SettingsNotConnectedException()
                                    current.contactService.setTelemetryPermissions(EntityKey(contact.radioId, contact.id), granted)
                                } })
                        } else TextButton(enabled = !busy, onClick = { run {
                            val current = live()
                            if (current.token.radioId != contact.radioId) throw SettingsNotConnectedException()
                            current.contactService.updateContactPreferences(EntityKey(contact.radioId, contact.id), isBlocked = false)
                        } }) { Text(stringResource(L.commonDelete)) }
                    }
                }
            }
            SettingsExternalDestination.BlockedChannelSenders -> {
                var senders by remember { mutableStateOf<List<BlockedChannelSenderDTO>?>(null) }
                val radio = container.appState.currentRadioId
                LaunchedEffect(version, radio) {
                    try { senders = radio?.let { container.processStore.fetchBlockedChannelSenders(it) }.orEmpty() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { feature.report("Could not load blocked channel senders", failure); senders = emptyList() }
                }
                if (senders == null) CircularProgressIndicator()
                else senders.orEmpty().forEach { sender ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(sender.name)
                        TextButton(enabled = !busy && radio != null, onClick = { run {
                            container.processStore.deleteBlockedChannelSender(requireNotNull(radio), sender.name)
                            container.sessions.current?.takeIf { it.token.radioId == radio }?.syncCoordinator
                                ?.refreshBlockedContactsCache(requireNotNull(radio), container.processStore)
                        } }) { Text(stringResource(L.commonDelete)) }
                    }
                }
            }
            SettingsExternalDestination.AppSystemSettings, SettingsExternalDestination.DeviceSelection,
            SettingsExternalDestination.MapsSettings, SettingsExternalDestination.OfflineMapSettings ->
                error("Platform/navigation destination must be routed before rendering")
        }
        if (busy) CircularProgressIndicator()
    }
}
