// AndroidOnly: WP-407 Register implemented settings screens with real process/session/SAF adapters.
package com.meshcoreone.android.app.navigation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.R
import com.meshcoreone.android.app.container.AppSettingsFeature
import com.meshcoreone.android.app.platform.*
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.settings.app.about.SupportScreenModel
import com.meshcoreone.android.feature.settings.app.about.ui.*
import com.meshcoreone.android.feature.settings.app.appearance.ThemeServiceAppearanceController
import com.meshcoreone.android.feature.settings.app.appearance.ui.AppearanceScreen
import com.meshcoreone.android.feature.settings.app.backup.*
import com.meshcoreone.android.feature.settings.app.backup.ui.*
import com.meshcoreone.android.feature.settings.app.diagnostics.*
import com.meshcoreone.android.feature.settings.app.diagnostics.ui.*
import com.meshcoreone.android.feature.settings.app.nodeconfig.*
import com.meshcoreone.android.feature.settings.device.*
import com.meshcoreone.android.feature.settings.device.ui.*
import com.meshcoreone.android.feature.settings.device.SettingsDetail as Page
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
internal fun AppSettingsContent(
    feature: AppSettingsFeature, destination: NavigationDestination, coordinator: NavigationCoordinator,
    nodeDocuments: NodeConfigDocuments? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = feature.container
    val permissions = rememberSettingsPermissions()
    val documents = rememberBackupDocuments()
    val configDocuments = nodeDocuments ?: rememberNodeConfigDocuments()
    val configHolder = remember(feature) { feature.nodeConfig(context.resources) }
    var configPage by remember { mutableStateOf<SettingsSubpage?>(null) }
    var external by remember { mutableStateOf<SettingsExternalDestination?>(null) }
    var publicKey by remember { mutableStateOf<com.meshcoreone.android.core.protocol.bytes.Bytes?>(null) }
    var licenses by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var rendering by remember { mutableStateOf(false) }
    val close = { external = null; publicKey = null; licenses = false; about = false; rendering = false; configPage = null }
    BackHandler(external != null || publicKey != null || licenses || about || rendering || configPage != null, close)
    val navigator = remember(coordinator) {
        SettingsNavigator { target ->
            when (target) {
                SettingsExternalDestination.AppSystemSettings -> context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                )
                SettingsExternalDestination.MapsSettings, SettingsExternalDestination.OfflineMapSettings -> coordinator.selectTab(AppTab.MAP)
                SettingsExternalDestination.DeviceSelection -> coordinator.navigate(FeatureRoute(FeatureId.ONBOARDING))
                else -> external = target
            }
        }
    }
    val logData = remember(feature) { appLogSource(feature, context) }
    val logDependencies = remember(feature, documents, logData) {
        LogExportDependencies(logData, object : LogDocumentPort {
            override suspend fun saveText(suggestedName: String, content: String) =
                when (val result = documents.save(suggestedName, content.toByteArray(Charsets.UTF_8))) {
                    is BackupSaveOutcome.Saved -> LogSaveOutcome.Saved(result.displayName)
                    BackupSaveOutcome.Cancelled -> LogSaveOutcome.Cancelled
                }
        }, ResourceLogExportStrings(context.resources), LogExportDiagnostics(feature::report))
    }
    val deps = remember(feature, permissions, navigator, documents) {
        feature.dependencies(permissions.notifications, permissions.location, navigator, object : DiagnosticsPort {
            override suspend fun createExportFile(): String? {
                val snapshot = logData.snapshot()
                return when (documents.save(LogExportFormatter.fileName(snapshot.exportedAt),
                    LogExportFormatter.format(snapshot).toByteArray(Charsets.UTF_8))) {
                    is BackupSaveOutcome.Saved -> documents.savedUri?.toString()
                    BackupSaveOutcome.Cancelled -> null
                }
            }
            override suspend fun clearDebugLogs() = logData.clearLogs()
        })
    }
    val holders = remember(deps, scope) { SettingsHolders(deps, SettingsEnvironment(scope, describe = { UiErrorMapper().message(it) })) }
    val device by deps.connection.connectedDevice.collectAsState()
    val snapshot by container.connectionManager.snapshot.collectAsState()
    val transport = if (!snapshot.state.isConnected) null
        else container.currentTransport
    val page = (destination as? NavigationDestination.Setting)?.selection?.let { Page.valueOf(it.name) }
    val done = { coordinator.back(); Unit }
    val error by feature.failure.collectAsState()
    if (error != null) AlertDialog(onDismissRequest = { feature.failure.value = null },
        text = { Text(uiString(UiErrorMapper().message(requireNotNull(error)))) },
        confirmButton = { TextButton(onClick = { feature.failure.value = null }) { Text(stringResource(L.commonOk)) } })
    when {
        configPage == SettingsSubpage.ConfigExport -> NodeConfigExportScreen(configHolder, configDocuments, close)
        configPage == SettingsSubpage.ConfigImport -> NodeConfigImportScreen(configHolder, configDocuments, close)
        licenses -> AppLicensesContent(onDone = close, onFailure = { feature.report("Could not load bundled license", it) })
        publicKey != null -> PublicKeyScreen(requireNotNull(publicKey), close)
        external != null -> AppSettingsExternal(feature, requireNotNull(external), close)
        about -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            TextButton(onClick = close) { Text(stringResource(L.commonDone)) }
            AboutSection(stringResource(R.string.platform_open_source_licenses), onNavigate = { target ->
                about = false
                when (target) {
                    com.meshcoreone.android.feature.settings.app.about.AboutDestination.LICENSES -> licenses = true
                    com.meshcoreone.android.feature.settings.app.about.AboutDestination.SUPPORT -> coordinator.navigateToSetting(SettingsDetail.SUPPORT)
                    com.meshcoreone.android.feature.settings.app.about.AboutDestination.FEEDBACK -> coordinator.navigateToSetting(SettingsDetail.FEEDBACK)
                }
            })
        }
        page == null -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                SettingsListScreen(device, transport, deps.regions, deps.radioCatalog, { coordinator.navigateToSetting(SettingsDetail.valueOf(it.name)) },
                    { coordinator.navigate(FeatureRoute(FeatureId.ONBOARDING)) }, UiText.Verbatim(appVersion(context)))
            }
            TextButton(onClick = { about = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(S.aboutHeader)) }
        }
        page == Page.DEVICE_INFO -> DeviceInfoScreen(holders.deviceInfo, navigator, { sub ->
            if (sub is SettingsSubpage.PublicKey) publicKey = sub.publicKey
        }, done)
        page == Page.RADIO -> RadioSettingsScreen(remember(holders) {
            RadioSettingsHolders(holders.radioWriteGate, holders.radioPreset, holders.pathHash, holders.advancedRadio,
                holders.presetLocationSession, deps.regions, deps.radioCatalog, deps.location, deps.subdivisions)
        }, navigator, done)
        page == Page.LOCATION -> LocationSettingsScreen(holders.location, navigator, done)
        page == Page.CONNECTION -> if (transport == TransportType.WIFI) AppWifiSettings(feature, device, done)
            else BluetoothSettingsScreen(holders.bluetoothPin, done)
        page == Page.ADVANCED -> AdvancedSettingsScreen(remember(holders) {
            AdvancedSettingsHolders(holders.advancedPage, holders.floodScope, holders.contacts, holders.staleCleanup,
                holders.telemetry, holders.directMessages, holders.batteryCurve, holders.deviceActions,
                holders.regenerateIdentity, holders.dangerZone, holders.diagnostics)
        }, navigator, device?.supportsDefaultFloodScope == true, { uri ->
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        }, done, done, nodeConfigurationSection = {
            NodeConfigSection(snapshot.state == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY,
                { configPage = SettingsSubpage.ConfigExport }, { configPage = SettingsSubpage.ConfigImport })
        })
        page == Page.NOTIFICATIONS -> NotificationSettingsScreen(holders.notifications, navigator, done)
        page == Page.CHATS -> {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { ChatSettingsScreen(holders.chat, navigator, done) }
                TextButton(onClick = { rendering = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(S.linkPreviewsHeader)) }
            }
            if (rendering) AppPreviewSettings(feature) { rendering = false }
        }
        page == Page.APPEARANCE -> AppearanceScreen(remember(feature) {
            ThemeServiceAppearanceController(requireNotNull(container.themeService), scope)
        })
        page == Page.LANGUAGE -> AppLanguageContent { feature.report("Could not change app language", it) }
        page == Page.BACKUP -> {
            val backup = remember(feature, documents) {
                BackupFeatureDependencies(requireNotNull(container.backupEngine), documents, object : BackupConnectionGate {
                    override val isRadioConnected = container.connectionManager.snapshot.map { it.state.isConnected }
                        .stateIn(scope, SharingStarted.Eagerly, container.appState.connectionState.isConnected)
                }, object : BackupImportEffects {
                    // The process engine applies committed effects before returning across a cancellable screen boundary.
                    override fun restoredDataChanged() = Unit
                    override fun channelSlotsAffected(slotsByRadio: Map<java.util.UUID, Set<Int>>) = Unit
                    override suspend fun refreshBlockedContactsCache() = Unit
                }, BackupDiagnostics(feature::report), ResourceBackupStrings(context.resources))
            }
            BackupRestoreScreen(backup)
            val engine = container.backupEngine as com.meshcoreone.android.app.container.AppBackupFeature
            val committed by engine.committed.collectAsState()
            committed?.let { outcome ->
                AlertDialog(onDismissRequest = {}, text = {
                    Column {
                        Text(uiString(UiErrorMapper().message(outcome.failure)))
                        val summary = ImportResultPresenter.present(outcome.result, backup.strings)
                        Text(summary.heroSubtitle)
                        summary.addedRows.forEach { Text("${it.label}: ${it.count}") }
                        summary.skippedRows.forEach { Text("${it.label}: ${it.count}") }
                        summary.droppedRows.forEach { Text("${it.label}: ${it.count}") }
                    }
                }, confirmButton = {
                    if (outcome.preferences != null) TextButton(onClick = {
                        scope.launch {
                            try { engine.completePreferences() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (problem: Exception) { feature.report("Preference completion failed; backup rows remain committed", problem) }
                        }
                    }) { Text(stringResource(L.commonTryAgain)) }
                }, dismissButton = {
                    if (outcome.preferences == null) TextButton(onClick = engine::acknowledge) { Text(stringResource(L.commonDone)) }
                })
            }
        }
        page == Page.SUPPORT -> SupportScreen(SupportScreenModel.create())
        page == Page.FEEDBACK -> FeedbackScreen({ DiagnosticsSection(logDependencies) })
        page == Page.MAPS -> AppMapSettings(feature, done)
        else -> error("Unregistered Settings page: $page")
    }
}

private fun appVersion(context: android.content.Context) =
    context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName.orEmpty()} (${it.longVersionCode})" }

private fun appLogSource(feature: AppSettingsFeature, context: android.content.Context) = object : LogExportDataSource {
    override suspend fun snapshot(): LogExportSnapshot {
        DebugLogBuffer.shared?.flush()
        val container = feature.container
        val device = container.appState.connectedDevice ?: container.appState.currentRadioId?.let {
            container.processStore.fetchDevices().firstOrNull { device -> device.radioId == it }
        }
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return LogExportSnapshot(Instant.now(), info.versionName.orEmpty(), info.longVersionCode.toString(),
            "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE,
            ExportConnection(container.appState.connectionState.name, container.connectionManager.snapshot.value.intent.toString(), null, null),
            device?.let { ExportDevice(it.nodeName, it.publicKeyPrefix.uppercaseHexString(), it.lastConnected,
                it.firmwareVersion.toInt(), it.firmwareVersionString, it.manufacturerName, it.buildDate,
                it.frequency.toLong(), it.bandwidth.toLong(), it.spreadingFactor.toInt(), it.codingRate.toInt(),
                it.txPower.toInt(), it.maxTxPower.toInt(), it.maxContacts.toInt(), it.maxChannels.toInt(),
                it.manualAddContacts, it.multiAcks.toInt(), container.appState.connectionState.isConnected) },
            null, ExportLogs.Entries(container.processStore.fetchDebugLogEntries(Instant.EPOCH).map {
                ExportLogEntry(it.timestamp, it.level.toString(), it.category, it.message)
            }))
    }
    override suspend fun clearLogs() { DebugLogBuffer.shared?.flush(); feature.container.processStore.clearDebugLogEntries() }
}

@Composable
private fun AppLanguageContent(report: (Throwable) -> Unit) {
    val context = LocalContext.current
    val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<Activity>().first()
    val selected = AppLanguages.selected(context)
    fun select(tag: String) {
        try { AppLanguages.select(activity, tag) }
        catch (failure: Exception) { report(failure) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(S.languageAppLanguageFooter))
        TextButton(onClick = { select("") }) { Text(stringResource(R.string.platform_system_language)) }
        Column(Modifier.selectableGroup()) {
            AppLanguages.supported.forEach { tag ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected == tag, role = Role.RadioButton) { select(tag) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val locale = Locale.forLanguageTag(tag)
                    Text(locale.getDisplayName(locale), Modifier.weight(1f).padding(end = 12.dp))
                    RadioButton(selected == tag, onClick = null)
                }
            }
        }
    }
}
