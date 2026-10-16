package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactProtocolErrorCode
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.toContactFrame
import one.meshcore.shared.contact.toMeshContact
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.meshcore.firmwareCode
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runExceptionFallback
import one.meshcore.shared.tools.NodeDiscoveryViewModel

/**
 * Discovers firmware-mesh *regions* (named flood-routing scopes) advertised by repeaters via
 * DISCOVER_REQ / filter 0x04, then [MeshSession.requestRegions] on each responder. Kotlin port of
 * iOS `RegionDiscoveryService`. Distinct from geographic onboarding "region".
 */
object RegionDiscoveryService {
    /** DISCOVER_REQ filter that requests only repeaters (iOS `NodeDiscoveryFilter.repeaters`). */
    const val REPEATERS_FILTER: Int = 0x04

    /** Listen window for DISCOVER_RESP, matching Tools → Node Discovery (15s). */
    const val LISTEN_DURATION_MS: Long = 15_000L

    sealed class Outcome {
        data object SendFailed : Outcome()
        data object NoRepeatersResponded : Outcome()
        data object ErrorLoadingRepeaters : Outcome()
        data class Completed(val newRegions: List<String>, val allRepeatersTableFull: Boolean) : Outcome()
    }

    /**
     * Probe inputs. [supportsAdHocRequest] gates non-contact responders (firmware v1.16+).
     * [events] defaults to the session stream.
     */
    data class DiscoverRequest(
        val session: MeshSession,
        val radioID: Uuid,
        val knownRegions: List<String>,
        val supportsAdHocRequest: Boolean,
        val loadContacts: suspend (Uuid) -> List<ContactDto>,
        val loadDiscoveredNodes: suspend (Uuid) -> List<DiscoveredNodeRecord>,
        val listenDurationMs: Long = LISTEN_DURATION_MS,
        val events: Flow<MeshEvent> = session.events,
    )

    /**
     * Probe for nearby repeaters, query their region lists, and return names not already known.
     */
    suspend fun discover(request: DiscoverRequest): Outcome {
        val discoveredPubkeys = collectDiscoverResponderKeys(
            request.session,
            request.events,
            request.listenDurationMs,
        ) ?: return Outcome.SendFailed
        if (discoveredPubkeys.isEmpty()) return Outcome.NoRepeatersResponded

        val queryTargets = loadQueryTargets(
            radioID = request.radioID,
            responders = discoveredPubkeys,
            supportsAdHocRequest = request.supportsAdHocRequest,
            loadContacts = request.loadContacts,
            loadDiscoveredNodes = request.loadDiscoveredNodes,
        ) ?: return Outcome.ErrorLoadingRepeaters
        if (queryTargets.isEmpty()) return Outcome.NoRepeatersResponded

        val (allRegions, anyTableFull) = queryRegionsFromTargets(request.session, queryTargets)
        val knownSet = request.knownRegions.toSet()
        val newRegions = allRegions.filterNot { it in knownSet }.sorted()
        return Outcome.Completed(newRegions = newRegions, allRepeatersTableFull = anyTableFull)
    }

    /**
     * Prefer contact rows (routing data) among [responders]; optionally fill from Discover for
     * non-contacts when [supportsAdHocRequest] is true.
     */
    fun buildRegionQueryTargets(
        responders: Set<ByteArray>,
        contacts: List<ContactDto>,
        discoveredNodes: List<DiscoveredNodeRecord>,
        supportsAdHocRequest: Boolean,
    ): List<MeshContact> {
        val responderKeys = responders.map { it.toList() }.toSet()
        val byKey = linkedMapOf<List<Byte>, MeshContact>()
        contacts
            .filter { it.contactType == ContactType.REPEATER && it.publicKey.toList() in responderKeys }
            .forEach { byKey[it.publicKey.toList()] = it.toMeshContact() }
        if (supportsAdHocRequest) {
            discoveredNodes
                .filter {
                    it.contactType == ContactType.REPEATER &&
                        it.publicKey.toList() in responderKeys &&
                        it.publicKey.toList() !in byKey
                }
                .forEach {
                    byKey[it.publicKey.toList()] = it.toContactFrame(nowEpochSeconds = 0L).toMeshContact()
                }
        }
        return byKey.values.toList()
    }

    private suspend fun collectDiscoverResponderKeys(
        session: MeshSession,
        events: Flow<MeshEvent>,
        listenDurationMs: Long,
    ): Set<List<Byte>>? = runExceptionFallback({ null }) {
        val tag = session.sendNodeDiscoverRequest(filter = REPEATERS_FILTER, prefixOnly = false)
        val tagBytes = NodeDiscoveryViewModel.tagToLittleEndianBytes(tag)
        val keys = mutableSetOf<List<Byte>>()
        withTimeoutOrNull(listenDurationMs) {
            events.collect { event ->
                if (event is MeshEvent.DiscoverResponseReceived &&
                    event.response.tag.contentEquals(tagBytes)
                ) {
                    keys += event.response.publicKey.toList()
                }
            }
        }
        keys
    }

    private suspend fun loadQueryTargets(
        radioID: Uuid,
        responders: Set<List<Byte>>,
        supportsAdHocRequest: Boolean,
        loadContacts: suspend (Uuid) -> List<ContactDto>,
        loadDiscoveredNodes: suspend (Uuid) -> List<DiscoveredNodeRecord>,
    ): List<MeshContact>? = runExceptionFallback({ null }) {
        buildRegionQueryTargets(
            responders = responders.map { it.toByteArray() }.toSet(),
            contacts = loadContacts(radioID),
            discoveredNodes = loadDiscoveredNodes(radioID),
            supportsAdHocRequest = supportsAdHocRequest,
        )
    }

    private suspend fun queryRegionsFromTargets(
        session: MeshSession,
        queryTargets: List<MeshContact>,
    ): Pair<Set<String>, Boolean> {
        val allRegions = mutableSetOf<String>()
        var anyTableFull = false
        coroutineScope {
            queryTargets.map { contact ->
                async { queryOneRepeater(session, contact) }
            }.awaitAll().forEach { result ->
                when (result) {
                    is QueryResult.Regions -> allRegions += result.regions
                    QueryResult.TableFull -> anyTableFull = true
                    QueryResult.OtherFailure -> Unit
                }
            }
        }
        return allRegions to anyTableFull
    }

    private suspend fun queryOneRepeater(session: MeshSession, contact: MeshContact): QueryResult = try {
        QueryResult.Regions(session.requestRegions(contact))
    } catch (e: CancellationException) {
        throw e
    } catch (e: MeshBridgeException) {
        if (isTableFullDeviceError(e)) QueryResult.TableFull else QueryResult.OtherFailure
    } catch (_: Exception) {
        QueryResult.OtherFailure
    }

    // Substring matching on the detail would read code=30 as code=3; the shared parser reads the field.
    internal fun isTableFullDeviceError(e: MeshBridgeException): Boolean =
        e.firmwareCode() == ContactProtocolErrorCode.TABLE_FULL

    private sealed class QueryResult {
        data class Regions(val regions: List<String>) : QueryResult()
        data object TableFull : QueryResult()
        data object OtherFailure : QueryResult()
    }
}
