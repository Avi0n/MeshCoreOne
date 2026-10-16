package one.meshcore.shared.room

import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.addCLIResponseObserver
import one.meshcore.shared.remotenode.isRoom
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.PersistentLogger

/**
 * Room-server admin interactions, mirroring the Swift `RoomAdminService`: status/telemetry request-response,
 * admin CLI (content- and FIFO-matched), and the status/telemetry/CLI handler slots the polling layer folds
 * late responses through. Room *authentication* is not here — it is [RoomServerService.joinRoom] over the
 * shared login path. A connected room session is [RoomServerService.getConnectedSession].
 *
 * One instance per live connection. The three handler slots are confined behind one mutex, mirroring the Swift
 * actor. CLI commands mutate a room's configuration, so they are admin-gated (`session.isAdmin`); the UI only
 * surfaces the management surface for an admin, and this is the service-layer enforcement of that contract.
 */
class RoomAdminService private constructor(
    status: RoomAdminStatusClient,
    cli: RoomAdminCliClient,
    handlers: RoomAdminHandlerSlots,
) : RoomStatusRequests by status,
    RoomAdminCli by cli,
    RoomAdminHandlers by handlers {
    constructor(
        adminSession: RoomAdminSession,
        store: RoomStore,
        cliCoordinator: NodeCliCoordinator? = null,
        auditLogger: CommandAuditLogger = CommandAuditLogger(),
        logger: PersistentLogger? = null,
    ) : this(
        RoomAdminStatusClient(adminSession, store),
        RoomAdminCliClient(adminSession, store),
        RoomAdminHandlerSlots(cliCoordinator, auditLogger, logger),
    ) {
        // Every room CLI reply reaches the registered handler, including one that lands after its request
        // timed out, so a settings section can fill a blank field without another round-trip.
        cliCoordinator?.addCLIResponseObserver { message, session, contact ->
            if (session.isRoom) invokeCLIHandler(contact, message.text)
        }
    }
}
