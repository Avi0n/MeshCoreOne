package one.meshcore.shared.meshcore

import kotlinx.coroutines.flow.StateFlow

/**
 * A `suspend`/`Flow` facade over the Swift `MeshCoreBridge`, mirroring the Swift `MeshCoreSession`
 * public API so Swift reference code translates mechanically. Every command is a `suspend` function
 * that returns when the device's outcome arrives (or throws [MeshBridgeException] on failure);
 * pushed device events surface on the hot [events] flow.
 *
 * Correlation: each command takes a monotonic request id, registers a [kotlinx.coroutines.CompletableDeferred], and is
 * dispatched fire-and-forget into the native bridge. The Swift side reports the outcome on
 * [one.meshcore.shared.meshcore.jni.MeshBridgeNativeCallbacks] keyed by that id, completing the deferred. The bridge is
 * fire-and-forget and thread-safe, so many commands may be in flight at once.
 *
 * Transport: outbound frames go to the injected [MeshTransport]; the link implementation drives the
 * inbound hooks ([deliverReceived], [reportConnected], [reportConnectFailed], [reportDisconnected]).
 *
 * Lifetime: [close] cancels every in-flight command, stops the event pump, and closes the confined
 * `SwiftArena`, which deterministically destroys the Swift bridge (cancelling its event task and
 * releasing the JNI callback references).
 */
class MeshCoreSession private constructor(private val host: MeshCoreSessionHost) :
    MeshSession,
    MeshSessionLink by LinkSessionCommands(host),
    MeshSessionMessaging by MessagingSessionCommands(host),
    MeshSessionContacts by ContactsSessionCommands(host),
    MeshSessionRemote by RemoteSessionCommands(host),
    MeshSessionRadio by RadioSessionCommands(host),
    MeshSessionFlood by FloodSessionCommands(host),
    MeshCoreSessionStats by StatsSessionCommands(host),
    MeshCoreSessionAdmin by AdminSessionCommands(host) {

    constructor(
        transport: MeshTransport,
        advertisesWriteWithoutResponse: Boolean = false,
        clientIdentifier: String = "MeshCore-Kotlin",
    ) : this(MeshCoreSessionHost(transport, advertisesWriteWithoutResponse, clientIdentifier))

    /** Number of active [events] collectors; lets a caller await a subscriber before triggering. */
    val eventSubscriberCount: StateFlow<Int>
        get() = host.eventSubscriberCount
}
