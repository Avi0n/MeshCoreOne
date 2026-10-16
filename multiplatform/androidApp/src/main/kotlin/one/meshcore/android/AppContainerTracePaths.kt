package one.meshcore.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.bestAvailableLocation
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.pathedit.toPathEditDiscoveredNode
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_trace_error_no_response
import one.meshcore.shared.resources.contacts_trace_error_send_failed
import one.meshcore.shared.resources.tools_intent_status_radio_fallback_name
import one.meshcore.shared.tools.TracePathWorkspace
import one.meshcore.shared.trace.TraceFailureMessages
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TracePathRequest
import one.meshcore.shared.trace.TracePathWaiter
import one.meshcore.shared.trace.TraceResult
import org.jetbrains.compose.resources.getString

internal fun AppContainer.buildRunTracePath(waiter: TracePathWaiter): suspend (ByteArray, Int, Int) -> TraceResult =
    { pathBytes, hashSize, flags ->
        val expectedRadio = currentRadioId()
        // The runner stores these on the failed result and the screen shows them verbatim, so a
        // literal here would reach the user in English whatever locale the device runs in.
        val failureMessages = TraceFailureMessages(
            noResponse = getString(Res.string.contacts_trace_error_no_response),
            sendFailed = getString(Res.string.contacts_trace_error_send_failed),
        )
        val deviceName = lastConnectionStore.deviceName()
            ?: getString(Res.string.tools_intent_status_radio_fallback_name)
        currentCoroutineContext().ensureActive()
        if (expectedRadio == null || currentRadioId() != expectedRadio) throw CancellationException()
        waiter.run(
            TracePathRequest(
                pathBytes = pathBytes,
                hashSize = hashSize,
                flags = flags,
                events = connectionManager.services?.session?.events ?: emptyFlow(),
                sendTrace = { tag, traceFlags, path ->
                    currentCoroutineContext().ensureActive()
                    if (currentRadioId() != expectedRadio) throw CancellationException()
                    val liveSession = connectionManager.services?.session
                        ?: throw MeshBridgeException(BridgeErrorCode.NOT_CONNECTED, failureMessages.sendFailed)
                    liveSession.sendTrace(tag = tag, authCode = TRACE_AUTH_CODE, flags = traceFlags, path = path)
                },
                deviceName = deviceName,
                failureMessages = failureMessages,
                nowMs = { System.currentTimeMillis() },
            ),
        )
    }
internal fun AppContainer.buildTracePathWorkspace(waiter: TracePathWaiter) = TracePathWorkspace(
    scope = appScope,
    dependencies = TracePathWorkspace.Dependencies(
        runner = { runTracePath.takeIf { connectionManager.services != null } },
        builder = TracePathBuilder.Dependencies(
            contactService = { contactService() },
            radioId = { currentRadioId() },
            supportsHashSizeOverride = { radioSnapshot()?.supportsTraceHashSizeOverride == true },
            discoveredNodes = { radio ->
                discoveredNodeStore()?.list(radio)?.map { it.toPathEditDiscoveredNode() } ?: emptyList()
            },
        ),
        deviceHashMode = { toolsCliLocal()?.deviceCapabilities()?.pathHashMode },
        deviceLocation = {
            bestAvailableLocation(
                locationService.currentLocation.value,
                locationSettingsSnapshot()?.takeIf { it.hasLocation }?.let { LatLng(it.latitude, it.longitude) },
            )
        },
        savedPathStore = savedTracePathStore,
        recents = RecentHopsStore(pathEditPreferences),
    ),
    waiter = waiter,
)
private const val TRACE_AUTH_CODE = 0L
