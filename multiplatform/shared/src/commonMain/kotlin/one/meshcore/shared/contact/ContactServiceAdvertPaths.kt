package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

internal suspend fun ContactService.snapshotPathLastModified(keys: List<ByteArray>, radioID: Uuid?): Map<String, Long> =
    advertDeltaSchedule.snapshotPathLastModified(keys, radioID)

internal suspend fun ContactService.escalateUndeliveredPathUpdates(
    drainedPath: List<ByteArray>,
    preRoundLastModified: Map<String, Long>,
    radioID: Uuid?,
) = advertDeltaSchedule.escalateUndeliveredPathUpdates(drainedPath, preRoundLastModified, radioID)

internal fun ContactService.scheduleDeltaSync(busy: Boolean = false) = advertDeltaSchedule.scheduleDeltaSync(busy)
