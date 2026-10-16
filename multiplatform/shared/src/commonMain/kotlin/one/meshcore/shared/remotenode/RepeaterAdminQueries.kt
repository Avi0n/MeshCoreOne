package one.meshcore.shared.remotenode

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.meshcore.NeighboursResponse
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.Uuid
import kotlin.time.Duration

internal class RepeaterAdminQueries(
    private val remoteNodeService: RemoteNodeService,
    private val cliCoordinator: NodeCliCoordinator,
    private val nodeSession: NodeSession,
    private val store: RemoteNodeStore,
    edges: RepeaterAdminEdges,
) {
    private val auditLogger = edges.auditLogger
    private val logger = edges.logger

    /** Requests one page of neighbours from a repeater (repeater-only), applying the binary timeout ceiling. */
    suspend fun requestNeighbors(sessionID: Uuid, page: NeighborPageQuery): NeighboursResponse {
        val session = store.fetchSessionById(sessionID)
        if (session == null || !session.isRepeater) throw RemoteNodeError.SessionNotFound
        auditLogger.logNeighborsRequest(session.publicKey, page.count, page.offset)
        val effectiveTimeout = page.timeout ?: RemoteOperationTimeoutPolicy.BINARY_MAXIMUM
        return loadNeighborPage(effectiveTimeout) {
            nodeSession.requestNeighbours(
                session.publicKey,
                page.count,
                page.offset,
                page.orderBy.raw,
                page.pubkeyPrefixLength,
            )
        }
    }

    /**
     * Fetches every neighbour page, paginating with [NeighboursPagination.INTER_PAGE_DELAY] between pages
     * and the maximum per-page count so a full table needs the fewest round-trips.
     */
    suspend fun fetchAllNeighbors(
        sessionID: Uuid,
        orderBy: NeighborSortOrder,
        pubkeyPrefixLength: Int,
        timeout: Duration?,
    ): NeighboursResponse {
        val response = NeighboursPagination.collectingAllPages { offset ->
            if (offset > 0) delay(NeighboursPagination.INTER_PAGE_DELAY)
            requestNeighbors(
                sessionID,
                NeighborPageQuery(
                    count = NEIGHBOR_PAGE_SIZE,
                    offset = offset,
                    orderBy = orderBy,
                    pubkeyPrefixLength = pubkeyPrefixLength,
                    timeout = timeout,
                ),
            )
        }
        if (response.neighbours.size < response.totalCount) {
            logger?.warning(
                "Neighbour pagination returned ${response.neighbours.size} of ${response.totalCount} entries",
            )
        }
        return response
    }

    suspend fun requestStatus(sessionID: Uuid, timeout: Duration?): StatusResponse =
        remoteNodeService.requestStatus(sessionID, timeout)

    suspend fun requestTelemetry(sessionID: Uuid, timeout: Duration?): TelemetryResponse =
        remoteNodeService.requestTelemetry(sessionID, timeout)

    suspend fun requestOwnerInfo(sessionID: Uuid, timeout: Duration?): OwnerInfoResponse =
        remoteNodeService.requestOwnerInfo(sessionID, timeout)

    suspend fun sendCommand(sessionID: Uuid, command: String, timeout: Duration): String =
        cliCoordinator.sendCLICommand(sessionID, command, timeout)

    suspend fun sendRawCommand(sessionID: Uuid, command: String, timeout: Duration): String =
        cliCoordinator.sendRawCLICommand(sessionID, command, timeout)

    private companion object {
        const val NEIGHBOR_PAGE_SIZE = 255
    }
}

/** Runs one neighbour page and maps transport failures onto [RemoteNodeError]. */
private suspend fun loadNeighborPage(timeout: Duration, load: suspend () -> NeighboursResponse): NeighboursResponse =
    try {
        withTimeoutOrNull(timeout) { load() } ?: throw RemoteNodeError.Timeout
    } catch (error: MeshError) {
        remoteNodeFailure(error)
    }
