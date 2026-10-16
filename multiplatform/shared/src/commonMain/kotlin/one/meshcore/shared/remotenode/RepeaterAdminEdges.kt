package one.meshcore.shared.remotenode

import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.PersistentLogger

/** Audit log and the optional repeater trace. A null logger stays quiet. */
data class RepeaterAdminEdges(
    val auditLogger: CommandAuditLogger = CommandAuditLogger(),
    val logger: PersistentLogger? = null,
)
