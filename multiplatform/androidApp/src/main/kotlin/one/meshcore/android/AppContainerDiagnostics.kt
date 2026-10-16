package one.meshcore.android

import android.content.pm.PackageInfo
import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.connection.bleDiagnosticsSummary
import one.meshcore.shared.connection.connectionIntentSummary
import one.meshcore.shared.connection.lastDisconnectDiagnostic
import one.meshcore.shared.connection.toDeviceConnectionState
import one.meshcore.shared.settings.BatteryDiagnostics
import one.meshcore.shared.settings.ConnectionDiagnostics
import one.meshcore.shared.settings.DebugLogExport
import one.meshcore.shared.settings.DeviceDiagnostics
import one.meshcore.shared.settings.PlatformDiagnostics
import one.meshcore.shared.settings.exportRetainedDebugLog

suspend fun AppContainer.exportDebugLogsText(): String = exportRetainedDebugLog(
    buffer = debugLogBuffer,
    store = debugLogStore,
    nowMillis = System.currentTimeMillis(),
) { entries ->
    DebugLogExport.format(
        entries,
        connection = connectionDiagnostics(),
        device = deviceDiagnostics(),
        battery = batteryDiagnostics(),
        platform = platformDiagnostics(),
    )
}
internal fun AppContainer.platformDiagnostics(): PlatformDiagnostics {
    val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    return PlatformDiagnostics(
        appVersion = packageInfo.appVersionNameOrMissing(),
        buildNumber = packageInfo.appBuildNumber(),
        deviceModel = android.os.Build.MODEL,
        osName = "Android",
        osVersion = android.os.Build.VERSION.RELEASE,
        exportedAtEpochMillis = System.currentTimeMillis(),
    )
}

/**
 * What the connection was doing, for the head of a diagnostics export. The last-disconnect summary is
 * the artifact a responder sends back after a radio drops, so it survives the disconnect that
 * produced it and the launch after it.
 */
internal suspend fun AppContainer.connectionDiagnostics(): ConnectionDiagnostics {
    val deviceId = lastConnectionStore.deviceId()
    return ConnectionDiagnostics(
        state = connectionManager.state.value.toDeviceConnectionState().name,
        intent = connectionManager.connectionIntentSummary(),
        lastDisconnectDiagnostic = connectionManager.lastDisconnectDiagnostic(),
        deviceName = lastConnectionStore.deviceName(),
        deviceId = deviceId?.value,
        lastConnectedEpochSeconds = deviceId?.let { database.deviceDao().getById(it)?.lastConnected },
        bleSummary = connectionManager.bleDiagnosticsSummary(),
    )
}
internal suspend fun AppContainer.deviceDiagnostics(): DeviceDiagnostics? {
    val deviceId = lastConnectionStore.deviceId() ?: return null
    val row = database.deviceDao().getById(deviceId) ?: return null
    return DeviceDiagnostics(
        isConnected = connectionManager.state.value.isLinkUp,
        name = row.nodeName,
        firmwareVersionString = row.firmwareVersionString,
        firmwareVersion = row.firmwareVersion,
        manufacturerName = row.manufacturerName,
        buildDate = row.buildDate,
        frequencyKHz = row.frequency,
        bandwidthHz = row.bandwidth,
        spreadingFactor = row.spreadingFactor,
        codingRate = row.codingRate,
        txPower = row.txPower,
        maxTxPower = row.maxTxPower,
        maxContacts = row.maxContacts,
        maxChannels = row.maxChannels,
        manualAddContacts = row.manualAddContacts,
        multiAcks = row.multiAcks,
    )
}
internal suspend fun AppContainer.batteryDiagnostics(): BatteryDiagnostics? {
    val info = appState.battery.value ?: return null
    val deviceId = lastConnectionStore.deviceId()
    val preset = deviceId?.let { database.deviceDao().getById(it)?.ocvPreset }
    val ocv = OCVPreset.fromRaw(preset.orEmpty())?.ocvArray.orEmpty()
    return BatteryDiagnostics(
        percentage = BatteryOcv.percentage(info, ocv),
        voltage = info.level / AppContainer.MV_PER_VOLT,
        millivolts = info.level,
    )
}

suspend fun AppContainer.clearDebugLogs() {
    debugLogStore.clearDebugLogEntries()
}

internal fun PackageInfo.appBuildNumber(): String = longVersionCode.toString()

internal fun PackageInfo.appVersionNameOrMissing(): String = versionName ?: MISSING_APP_VERSION_NAME

private const val MISSING_APP_VERSION_NAME = "0.0.0"
