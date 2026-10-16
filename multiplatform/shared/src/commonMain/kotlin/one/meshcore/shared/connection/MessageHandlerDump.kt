package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid

/** iOS `Wiring message handlers for device \(radioID)`. */
fun wiringMessageHandlersLine(radioId: Uuid): String = "Wiring message handlers for device ${radioId.value}"

/** iOS `Message handlers wired successfully`. */
const val MESSAGE_HANDLERS_WIRED = "Message handlers wired successfully"

/** iOS `Starting discovery event monitoring for device \(radioID)`. */
fun startingDiscoveryEventMonitoringLine(radioId: Uuid): String =
    "Starting discovery event monitoring for device ${radioId.value}"
