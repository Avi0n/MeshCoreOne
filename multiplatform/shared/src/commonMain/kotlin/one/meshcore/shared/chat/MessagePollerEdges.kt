package one.meshcore.shared.chat

import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.settings.PersistentLogger

/** Clock, failure hook, and the CLI and signed handlers the poller calls outside the ingest pipeline. */
data class MessagePollerEdges(
    val now: () -> Double,
    val signedMessageHandler: suspend (ContactMessage, ResolvedContact?) -> Unit = { _, _ -> },
    val cliMessageHandler: suspend (ContactMessage, ResolvedContact?) -> Unit = { _, _ -> },
    val onIngestFailure: (Throwable) -> Unit = { },
    val materializePendingContact: suspend (ByteArray) -> ResolvedContact? = { null },
    val logger: PersistentLogger? = null,
)
