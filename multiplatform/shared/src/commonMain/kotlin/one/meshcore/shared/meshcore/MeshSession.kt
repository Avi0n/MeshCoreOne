package one.meshcore.shared.meshcore

/** The six fields one setOtherParams frame carries. A null multiAcks leaves that field unset on the wire. */
data class DeviceOtherParams(
    val manualAddContacts: Boolean,
    val telemetryModeEnvironment: Int,
    val telemetryModeLocation: Int,
    val telemetryModeBase: Int,
    val advertisementLocationPolicy: Int,
    val multiAcks: Int?,
)

/**
 * The slice of the device session the connection layer depends on. `MeshCoreSession` (androidMain,
 * over the real Swift bridge) is the production implementation; a fake stands in for the pure-JVM
 * state-machine tests. Extracting this seam keeps `ConnectionManager` and `ServiceContainer` in
 * commonMain, along with the chat/contact/node-config command subset the per-connection service graph
 * drives (send, backlog drain, path reset, contact ops, radio/identity config). Diagnostics and other
 * seldom-used commands may still live only on [MeshCoreSession].
 *
 * The inbound hooks ([reportConnected], [reportConnectFailed], [reportDisconnected],
 * [deliverReceived]) are driven by the manager from the link's `MeshLinkListener`; everything else is
 * a command the manager or a store service issues.
 */
interface MeshSession :
    MeshSessionLink,
    MeshSessionMessaging,
    MeshSessionContacts,
    MeshSessionRemote,
    MeshSessionRadio,
    MeshSessionFlood
