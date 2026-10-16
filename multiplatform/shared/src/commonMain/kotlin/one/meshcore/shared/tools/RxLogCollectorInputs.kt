package one.meshcore.shared.tools

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

data class RxLogCollectorInputs(
    val store: RxLogStore,
    val radioId: () -> Uuid?,
    val nowEpochMs: () -> Long,
    val decodeCache: RxLogDecodeCache = RxLogDecodeCache(),
    val onEntryAppended: suspend (RxLogEntry) -> Unit = {},
    val onRowsRederived: suspend (List<RxLogEntry>) -> Unit = {},
    val logger: PersistentLogger? = null,
)
