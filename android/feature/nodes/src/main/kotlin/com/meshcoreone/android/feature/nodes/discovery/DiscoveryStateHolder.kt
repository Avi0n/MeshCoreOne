// PortedFrom: MC1/Views/Contacts/DiscoveryViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/DiscoveryView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.discovery

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.model.DiscoverSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoveryState(
    val discoveredNodes: List<DiscoveredNodeDTO> = emptyList(),
    /** Public keys of nodes already added as contacts. */
    val addedPublicKeys: Set<Bytes> = emptySet(),
    /** Nodes matching the last search/segment/sort inputs. */
    val visibleNodes: List<DiscoveredNodeDTO> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val errorMessage: NodesMessage? = null,
    /** Node whose add command is in flight. */
    val addingNodeId: UUID? = null,
)

/** Discovered-node list (`DiscoveryViewModel` plus the add/clear actions from `DiscoveryView`). */
class DiscoveryStateHolder(
    private val dependencies: NodesFeatureDependencies,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = mutableState.asStateFlow()

    private data class FilterInputs(
        val searchText: String = "",
        val segment: DiscoverSegment = DiscoverSegment.ALL,
        val sortOrder: NodeSortOrder = NodeSortOrder.LAST_HEARD,
        val userLocation: Coordinate? = null,
    )

    /** Last filter inputs, re-applied whenever the underlying data changes. */
    private var lastInputs = FilterInputs()
    private var reloadJob: Job? = null
    private val session get() = dependencies.session

    /** Test/preview seam for the Swift model's publicly settable properties. */
    internal fun seed(transform: (DiscoveryState) -> DiscoveryState) = mutableState.update(transform)

    suspend fun loadDiscoveredNodes() {
        val dataStore = session.offlineDataStore() ?: return
        val radioId = session.connectedDevice()?.radioId ?: return
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val nodes = dataStore.fetchDiscoveredNodes(radioId)
            // One batch query for all contact public keys, not one round-trip per node.
            val addedKeys = dataStore.fetchContactPublicKeys(radioId)
            mutableState.update { it.copy(discoveredNodes = nodes, addedPublicKeys = addedKeys) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = failure(error)) }
        }
        mutableState.update { it.copy(hasLoadedOnce = true, isLoading = false) }
        applyFilter()
    }

    /** Debounced reload so bursts of contacts-version bumps trigger one load; no-op while one is pending. */
    fun scheduleCoalescedReload() {
        if (reloadJob != null) return
        reloadJob = scope.launch {
            dependencies.clock.sleep(RELOAD_DEBOUNCE)
            reloadJob = null
            loadDiscoveredNodes()
        }
    }

    fun isAdded(node: DiscoveredNodeDTO): Boolean = node.publicKey in state.value.addedPublicKeys

    suspend fun deleteDiscoveredNode(node: DiscoveredNodeDTO) {
        val dataStore = session.offlineDataStore() ?: return
        mutableState.update { current -> current.copy(discoveredNodes = current.discoveredNodes.filterNot { it.id == node.id }) }
        applyFilter()
        reportingFailure { dataStore.deleteDiscoveredNode(EntityKey(node.radioId, node.id)) }
    }

    /** Clears every discovered node; returns true on success so the UI can announce it. */
    suspend fun clearAllDiscoveredNodes(): Boolean {
        val dataStore = session.offlineDataStore() ?: return false
        val radioId = session.connectedDevice()?.radioId ?: return false
        return reportingFailure {
            dataStore.clearDiscoveredNodes(radioId)
            mutableState.update { it.copy(discoveredNodes = emptyList()) }
            applyFilter()
        }
    }

    /** Announces the clear, as `DiscoveryView.clearAllDiscoveredNodes` does after the model call. */
    suspend fun clearAllAndAnnounce() {
        clearAllDiscoveredNodes()
        dependencies.announcer.announce(NodesMessage.res(R.string.l10n_app_contacts_contacts_discovery_clearedallnodes))
    }

    /** Adds a discovered node as a contact, then reloads (`DiscoveryView.addNode`). */
    suspend fun addNode(node: DiscoveredNodeDTO) {
        val contactService = session.contactService() ?: return
        mutableState.update { it.copy(addingNodeId = node.id) }
        try {
            contactService.addOrUpdateContact(node.radioId, node.contactFrame(dependencies.clock.wallNow.epochSecond.toUInt()))
            loadDiscoveredNodes()
        } catch (_: NodesContactFailure.ContactTableFull) {
            val maxContacts = session.connectedDevice()?.maxContacts
            val message = if (maxContacts != null) {
                NodesMessage.res(R.string.l10n_app_contacts_contacts_add_error_nodelistfull, maxContacts.toInt())
            } else {
                NodesMessage.res(R.string.l10n_app_contacts_contacts_add_error_nodelistfullsimple)
            }
            mutableState.update { it.copy(errorMessage = message) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = failure(error)) }
        } finally {
            mutableState.update { it.copy(addingNodeId = null) }
        }
    }

    /** Recomputes visible nodes from the given inputs; the UI calls it on input changes only. */
    fun updateVisibleNodes(searchText: String, segment: DiscoverSegment, sortOrder: NodeSortOrder, userLocation: Coordinate?) {
        lastInputs = FilterInputs(searchText, segment, sortOrder, userLocation)
        applyFilter()
    }

    private fun applyFilter() {
        val inputs = lastInputs
        val visible = filteredNodes(inputs.searchText, inputs.segment, inputs.sortOrder, inputs.userLocation)
        mutableState.update { it.copy(visibleNodes = visible) }
    }

    fun filteredNodes(
        searchText: String,
        segment: DiscoverSegment,
        sortOrder: NodeSortOrder,
        userLocation: Coordinate?,
    ): List<DiscoveredNodeDTO> =
        DiscoverySorting.filtered(state.value.discoveredNodes, searchText, segment, sortOrder, userLocation, dependencies.locale())

    fun clearError() = mutableState.update { it.copy(errorMessage = null) }

    private suspend fun reportingFailure(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        mutableState.update { it.copy(errorMessage = failure(error)) }
        false
    }

    private fun failure(error: Throwable) = NodesMessage.Text(dependencies.messages.message(error))

    companion object {
        val RELOAD_DEBOUNCE = 50.milliseconds

        /** Path prefixes for a discovered row: more than six hops collapse to first three and last three. */
        fun formattedPath(nodes: List<String>): String =
            if (nodes.size > 6) nodes.take(3).joinToString(",") + "\u2026" + nodes.takeLast(3).joinToString(",")
            else nodes.joinToString(",")
    }
}

/** `DiscoveredNodeDTO.makeContactFrame(lastModified:)` (WP-304 owns the shared extension). */
internal fun DiscoveredNodeDTO.contactFrame(lastModified: UInt): ContactFrame = ContactFrame(
    publicKey = publicKey, type = nodeType, flags = 0u, outPathLength = outPathLength, outPath = outPath, name = name,
    lastAdvertTimestamp = lastAdvertTimestamp, latitude = latitude, longitude = longitude, lastModified = lastModified,
    typeRawValue = typeRawValue,
)
