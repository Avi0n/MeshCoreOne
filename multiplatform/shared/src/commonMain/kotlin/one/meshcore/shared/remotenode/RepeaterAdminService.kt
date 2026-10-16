package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.meshcore.NeighboursResponse
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.Uuid
import kotlin.time.Duration

/**
 * The repeater-admin surface, mirroring the Swift `RepeaterAdminService`. A thin per-connection service
 * over the pieces this port already builds: [RemoteNodeService] (auth, status/telemetry/owner-info) and
 * [NodeCliCoordinator] (CLI), plus repeater-only neighbour queries over [NodeSession] and the push-handler
 * registry the status/settings view models drive. Neighbour ordering reuses [NeighborSortOrder].
 */
class RepeaterAdminService private constructor(private val parts: RepeaterAdminParts) :
    RepeaterPushHandlers by parts.handlers,
    RepeaterAdminSessions by parts.sessions {
    constructor(
        remoteNodeService: RemoteNodeService,
        cliCoordinator: NodeCliCoordinator,
        nodeSession: NodeSession,
        store: RemoteNodeStore,
        passwordStore: NodePasswordStore,
        edges: RepeaterAdminEdges = RepeaterAdminEdges(),
    ) : this(
        RepeaterAdminParts(
            remoteNodeService,
            cliCoordinator,
            nodeSession,
            store,
            passwordStore,
            edges,
        ),
    )

    init {
        // Every repeater CLI reply reaches the registered handler, including one that lands after its
        // request timed out, so a settings section can fill a blank field without another round-trip.
        parts.cliCoordinator.addCLIResponseObserver { message, session, contact ->
            if (session.isRepeater) invokeCLIHandler(message, contact)
        }
    }

    /**
     * Connects to a repeater as admin: creates the session, logs in, stores the password only after a
     * successful login (when [rememberPassword]), and returns the updated session.
     */
    suspend fun connectAsAdmin(
        radioID: Uuid,
        contact: ContactDto,
        password: String?,
        login: RepeaterAdminLogin = RepeaterAdminLogin(),
    ): RemoteNodeSessionDto = parts.sessions.connectAsAdmin(radioID, contact, password, login)

    /** Requests one page of neighbours from a repeater (repeater-only), applying the binary timeout ceiling. */
    suspend fun requestNeighbors(sessionID: Uuid, page: NeighborPageQuery = NeighborPageQuery()): NeighboursResponse =
        parts.queries.requestNeighbors(sessionID, page)

    /**
     * Fetches every neighbour page, paginating with [NeighboursPagination.INTER_PAGE_DELAY] between pages
     * and the maximum per-page count so a full table needs the fewest round-trips.
     */
    suspend fun fetchAllNeighbors(
        sessionID: Uuid,
        orderBy: NeighborSortOrder = NeighborSortOrder.NEWEST_FIRST,
        pubkeyPrefixLength: Int = NeighborPageQuery.DEFAULT_PUBKEY_PREFIX_LENGTH,
        timeout: Duration? = null,
    ): NeighboursResponse = parts.queries.fetchAllNeighbors(sessionID, orderBy, pubkeyPrefixLength, timeout)

    suspend fun requestStatus(sessionID: Uuid, timeout: Duration? = null): StatusResponse =
        parts.queries.requestStatus(sessionID, timeout)

    suspend fun requestTelemetry(sessionID: Uuid, timeout: Duration? = null): TelemetryResponse =
        parts.queries.requestTelemetry(sessionID, timeout)

    suspend fun requestOwnerInfo(sessionID: Uuid, timeout: Duration? = null): OwnerInfoResponse =
        parts.queries.requestOwnerInfo(sessionID, timeout)

    suspend fun sendCommand(sessionID: Uuid, command: String, timeout: Duration = DEFAULT_CLI_TIMEOUT): String =
        parts.queries.sendCommand(sessionID, command, timeout)

    suspend fun sendRawCommand(sessionID: Uuid, command: String, timeout: Duration = DEFAULT_CLI_TIMEOUT): String =
        parts.queries.sendRawCommand(sessionID, command, timeout)

    /**
     * Reply body the in-flight waiter just accepted, or null when this fan-out is uncorrelated.
     * Only meaningful inside the CLI handler: the coordinator clears it when fan-out returns.
     */
    fun cliBodyClaimedByWaiter(): String? = parts.cliCoordinator.cliBodyClaimedByWaiter()

    private companion object {
        val DEFAULT_CLI_TIMEOUT: Duration = RemoteOperationTimeoutPolicy.DEFAULT_CLI
    }
}

private class RepeaterAdminParts(
    remoteNodeService: RemoteNodeService,
    val cliCoordinator: NodeCliCoordinator,
    nodeSession: NodeSession,
    store: RemoteNodeStore,
    passwordStore: NodePasswordStore,
    edges: RepeaterAdminEdges,
) {
    val handlers = RepeaterAdminHandlers(edges)
    val sessions = RepeaterAdminSessionAccess(remoteNodeService, store, passwordStore)
    val queries = RepeaterAdminQueries(remoteNodeService, cliCoordinator, nodeSession, store, edges)
}
