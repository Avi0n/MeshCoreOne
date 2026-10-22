// PortedFrom: MC1Services/Sources/MC1Services/ServiceContainer.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: narrow injected process, physical-link and generation-service roles.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.SessionInputs
import com.meshcoreone.android.core.contracts.domain.SessionServices
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import java.util.UUID

data class PlatformLinkState(
    val connected: Boolean,
    val autoReconnecting: Boolean,
    val bluetoothPoweredOff: Boolean,
    val connectedDeviceId: UUID?,
    val phase: String,
    val systemConnected: Boolean,
)

/** Android CDM/permissions/background hosting belongs to WP-206/303, not this JVM module. */
interface ConnectionPlatform {
    val hasSystemPairingRegistry: Boolean
    val registryActive: Boolean
    suspend fun activate()
    suspend fun foreground(active: Boolean)
    suspend fun state(target: ConnectionTarget): PlatformLinkState
    suspend fun isRegistered(deviceId: UUID): Boolean
    suspend fun targetForDevice(deviceId: UUID): ConnectionTarget?
    suspend fun adoptSystemLink(target: ConnectionTarget): Boolean
    fun classifyFailure(failure: Throwable): LinkFailure?
}

interface ProcessRuntimeMaintenance {
    suspend fun warmUp()
    suspend fun initializeDevicePreferences(device: DeviceDTO)
}

data class LinkCallbacks(
    val onDisconnected: (Throwable?) -> Unit,
    val onAutoReconnecting: (String) -> Unit,
    val onReconnected: () -> Unit,
    val onBondRefreshed: () -> Unit,
)

interface RuntimeLink {
    val transport: MeshTransport
    val type: TransportType
    fun register(callbacks: LinkCallbacks): AutoCloseable
    suspend fun configure(capabilities: DeviceCapabilities, platform: DevicePlatform)
    suspend fun setSessionLive(token: SessionToken?)
    suspend fun recordBondVerification(deviceId: UUID, at: java.time.Instant)
    suspend fun clearBondVerification(deviceId: UUID)
    suspend fun mayRefreshBond(deviceId: UUID): Boolean
}

/** Creation allocates only; register() installs handlers before start can activate a physical link. */
fun interface RuntimeLinkFactory { fun create(target: ConnectionTarget): RuntimeLink }

sealed interface RuntimeSyncResult {
    data object Usable : RuntimeSyncResult
    data class Failed(val cause: Throwable) : RuntimeSyncResult
}

data class RuntimeServiceCallbacks(
    val cleanChannelSync: suspend () -> Unit,
    val channelSyncAttempted: suspend () -> Unit,
    val reconcileIdentity: suspend () -> RadioId?,
)

data class RuntimeServiceInputs(
    val connection: SessionInputs<MeshCoreSession>,
    val device: DeviceDTO,
    val callbacks: RuntimeServiceCallbacks,
)

/** Implemented by real WPs 208-218 at WP-303; no concrete or no-op graph is constructed here. */
interface RuntimeServices : SessionServices {
    suspend fun hydrate()
    suspend fun initialSync(forceFullSync: Boolean): RuntimeSyncResult
    suspend fun remoteDisconnected(): Set<UUID>
    suspend fun reauthenticate(sessionIds: Set<UUID>)
    suspend fun resetSyncState()
    suspend fun ensureListeners()
}

fun interface RuntimeServiceFactory {
    suspend fun create(inputs: RuntimeServiceInputs, ownership: FactoryOwnership): RuntimeServices
}

data class ConnectionObserver(
    val onServicesAvailable: suspend (SessionToken) -> Unit,
    val onConnectionLost: suspend () -> Unit,
    val onAutoReconnectStarted: suspend () -> Unit,
    val onDeviceSynced: suspend (SessionToken) -> Unit,
    val onAuthenticationFailure: (UUID) -> Unit,
    val onLastDeviceCleared: () -> Unit,
)

sealed interface RuntimeDiagnostic {
    data class OperationSubmitted(val revision: Long) : RuntimeDiagnostic
    data class Failure(val operation: String, val cause: Throwable) : RuntimeDiagnostic
    data class StaleCallback(val operation: String, val generation: Long) : RuntimeDiagnostic
    data class Teardown(val report: com.meshcoreone.android.core.contracts.domain.TeardownReport) : RuntimeDiagnostic
}

fun interface RuntimeIssueReporter { fun report(diagnostic: RuntimeDiagnostic) }
