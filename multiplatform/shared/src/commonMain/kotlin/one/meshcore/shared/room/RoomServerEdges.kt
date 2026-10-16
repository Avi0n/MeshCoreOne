package one.meshcore.shared.room

import one.meshcore.shared.chat.MessageServiceConfig
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.PersistentLogger

/**
 * Clock, retry config, audit log, and trace for one room-server connection.
 * A null clock or logger uses the live default.
 */
data class RoomServerEdges(
    val config: MessageServiceConfig = MessageServiceConfig(),
    val newId: (() -> Uuid)? = null,
    val now: (() -> Long)? = null,
    val auditLogger: CommandAuditLogger = CommandAuditLogger(),
    val logger: PersistentLogger? = null,
)
