package one.meshcore.shared.transport

import one.meshcore.shared.runReportingThrowable

/**
 * Runs one BLE link operation. Cancellation leaves the link alone. Any other failure, including an
 * [Error], is handed to [onFailure] so a dead write or notification pump can drop the GATT link.
 */
internal suspend fun <T> runBleLink(onFailure: suspend (Throwable) -> T, block: suspend () -> T): T =
    runReportingThrowable(onFailure, block)
