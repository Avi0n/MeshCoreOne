package one.meshcore.shared.tools

import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.model.Uuid

interface RxLogStore {
    val snapshot: StateFlow<List<RxLogEntry>>

    suspend fun list(radioID: Uuid): List<RxLogEntry>

    suspend fun clear(radioID: Uuid)

    suspend fun append(entry: RxLogEntry)

    /**
     * Re-derives plaintext, channel attribution and flood region for every held row against [decode],
     * returning the rows whose outcome changed. A secret, DM key or region that arrives after a packet
     * was logged only reaches that packet through this pass.
     */
    suspend fun redecrypt(decode: RxLogDecodeCache.DecodeSnapshot): List<RxLogEntry>
}
