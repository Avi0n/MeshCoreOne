package one.meshcore.shared.connection

import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.settings.PersistentLogger

/** iOS `ConnectionManager.reconcileIdentity` when `currentSelfInfo` is missing. */
internal const val RECONCILE_SELF_INFO_NIL = "reconcileIdentity failed: session.currentSelfInfo is nil"

/** iOS `ConnectionManager.reconcileIdentity` when ghost persist throws. */
internal const val RECONCILE_GHOST_FAILED_PREFIX = "reconcileIdentity: reconcileGhostIdentity failed"

/**
 * Reads radio self-info for identity reconcile. A missing snapshot is logged and yields null so
 * import can keep the live partition. Cancellation still stops the caller.
 */
internal suspend fun readSelfInfoForReconcile(
    getSelfInfo: suspend () -> SelfInfo,
    logger: PersistentLogger,
): SelfInfo? = runConnectionPersist(
    onFailure = { _ -> logger.warning(RECONCILE_SELF_INFO_NIL) },
    fallback = { null },
    block = getSelfInfo,
)

/**
 * Reads the device rows for identity reconcile. A Room fault is logged and yields null so import
 * keeps the live partition. Cancellation still stops the caller.
 */
internal suspend fun readDevicesForReconcile(
    listAll: suspend () -> List<DeviceEntity>,
    logger: PersistentLogger,
): List<DeviceEntity>? = runConnectionPersist(
    onFailure = { error -> logger.warning("$RECONCILE_GHOST_FAILED_PREFIX: ${error.message ?: error}") },
    fallback = { null },
    block = listAll,
)

/**
 * Applies the ghost/public-key persist after reconcile. A Room fault is logged and returns false so
 * import does not abort after the radio already adopted the new key. Cancellation still stops the
 * caller.
 */
internal suspend fun applyGhostReconcilePersist(apply: suspend () -> Unit, logger: PersistentLogger): Boolean =
    runConnectionPersist(
        onFailure = { error -> logger.warning("$RECONCILE_GHOST_FAILED_PREFIX: ${error.message ?: error}") },
        fallback = { false },
    ) {
        apply()
        true
    }
