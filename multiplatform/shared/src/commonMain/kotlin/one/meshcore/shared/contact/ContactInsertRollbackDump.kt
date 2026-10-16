package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/** iOS `AdvertisementService.rollbackInsertIfNeeded` persist-fail dump. */
fun insertOnlyRollbackFailedLine(id: Uuid, detail: String): String =
    "Insert-only rollback failed for ${id.value}: $detail"
