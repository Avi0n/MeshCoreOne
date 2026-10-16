package one.meshcore.shared.contact

import kotlinx.coroutines.channels.ReceiveChannel
import one.meshcore.shared.model.toUppercaseHex

/** Public keys of advertisement-applied events already buffered, so a fault test can assert who applied. */
internal suspend fun appliedKeys(events: ReceiveChannel<ContactServiceEvent>): List<String> {
    val applied = mutableListOf<String>()
    while (true) {
        val event = events.tryReceive().getOrNull() ?: break
        if (event is ContactServiceEvent.ContactAdvertisementApplied) applied += event.publicKey.toUppercaseHex()
    }
    return applied
}
