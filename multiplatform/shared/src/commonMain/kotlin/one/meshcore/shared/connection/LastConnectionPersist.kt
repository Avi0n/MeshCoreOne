package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/** Dump when the last-disconnect diagnostic write throws. */
const val FAILED_PERSIST_DISCONNECT_DIAGNOSTIC = "Failed to persist disconnect diagnostic"

/** Dump when the bond-verification stamp write throws. */
const val FAILED_PERSIST_BOND_VERIFICATION = "Failed to persist bond verification"

/** Dump when the last-connected radio record write throws. */
const val FAILED_PERSIST_LAST_CONNECTION = "Failed to persist last connection"

/** Dump when the last-connected radio record read throws. */
const val FAILED_READ_LAST_CONNECTION = "Failed to read last connection"

/** Dump when the connection-intent prefs write throws. */
const val FAILED_PERSIST_CONNECTION_INTENT = "Failed to persist connection intent"

/** Dump when the last-connection record clear throws. */
const val FAILED_CLEAR_LAST_CONNECTION = "Failed to clear last connection"

/**
 * Applies a last-connection prefs write. A DataStore fault is logged so reconnect, Ready, and the
 * bond-refresh loop stay alive. Cancellation still stops the caller.
 */
suspend fun applyLastConnectionPersist(persist: suspend () -> Unit, logger: PersistentLogger, failLine: String) {
    runConnectionPersist(
        onFailure = { error -> logger.warning("$failLine: ${error.message ?: error}") },
        fallback = {},
        block = persist,
    )
}

/**
 * Reads whether the last-connection slot is empty. A DataStore fault is logged and treated as
 * still remembered so a prefs error cannot skip reconnect. Cancellation still stops the caller.
 */
suspend fun readLastConnectionForgotten(readDeviceId: suspend () -> Uuid?, warning: (String) -> Unit): Boolean =
    runConnectionPersist(
        onFailure = { error -> warning("$FAILED_READ_LAST_CONNECTION: ${error.message ?: error}") },
        fallback = { false },
    ) {
        readDeviceId() == null
    }

/**
 * Reads the last-connected radio id. A DataStore fault is logged and [fallback] is returned so
 * deleting that row still tears the live session down. Cancellation still stops the caller.
 */
suspend fun readLastConnectedDeviceId(
    readDeviceId: suspend () -> Uuid?,
    warning: (String) -> Unit,
    fallback: Uuid,
): Uuid? = runConnectionPersist(
    onFailure = { error -> warning("$FAILED_READ_LAST_CONNECTION: ${error.message ?: error}") },
    fallback = { fallback },
    block = readDeviceId,
)

/**
 * Reads the radio id for a stored-channel reload. A DataStore fault is logged and returned as null
 * so the reload is skipped. Cancellation still stops the caller.
 */
suspend fun readLastConnectionRadioId(readRadioId: suspend () -> Uuid?, warning: (String) -> Unit): Uuid? =
    runConnectionPersist(
        onFailure = { error -> warning("$FAILED_READ_LAST_CONNECTION: ${error.message ?: error}") },
        fallback = { null },
        block = readRadioId,
    )
