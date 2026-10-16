package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Known-region edits and nearby-region discovery for the channel info sheet. */
internal class ChannelInfoRegions(
    private val dependenciesProvider: () -> ChannelInfoController.Dependencies,
    private val state: MutableStateFlow<ChannelInfoState>,
) {
    private val dependencies: ChannelInfoController.Dependencies
        get() = dependenciesProvider()

    /**
     * Discovers nearby firmware mesh regions. New names are not persisted here — a non-empty result
     * opens the discovery-results multi-select ([ChannelInfoState.discoveredRegions]); an empty or
     * failed result lands in [ChannelInfoState.discoveryMessage]. Mirrors iOS ChannelInfoSheet
     * `runDiscovery` / `discoverNewRegions`.
     */
    suspend fun discoverNearbyRegions() {
        val discover = dependencies.discoverNearbyRegions ?: return
        if (state.value.isDiscoveringRegions) return
        state.update { it.copy(isDiscoveringRegions = true, discoveryMessage = null) }
        try {
            applyDiscoverResult(discover())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val message = dependencies.regionDiscoverMessage(RegionDiscoverUiResult.SendFailed)
            state.update { it.copy(isDiscoveringRegions = false, discoveryMessage = message) }
        }
    }

    private suspend fun applyDiscoverResult(result: RegionDiscoverUiResult) {
        val newRegions = (result as? RegionDiscoverUiResult.Completed)?.newRegions.orEmpty()
        if (newRegions.isNotEmpty()) {
            state.update {
                it.copy(isDiscoveringRegions = false, discoveredRegions = newRegions, discoveryMessage = null)
            }
        } else {
            val message = dependencies.regionDiscoverMessage(result)
            state.update { it.copy(isDiscoveringRegions = false, discoveryMessage = message) }
        }
    }

    /** Persists the user's selected discovered regions and closes the results screen. */
    suspend fun confirmDiscoveredRegions(selected: List<String>) {
        for (region in selected) {
            persistAddedRegion(region)
        }
        state.update { it.copy(discoveredRegions = null) }
    }

    /** Dismisses the discovery-results screen without adding any region. */
    fun dismissDiscoveryResults() = state.update { it.copy(discoveredRegions = null) }

    /** Adds a manually-entered region to Device.knownRegions (from the manage-regions screen). */
    suspend fun addRegion(region: String) = persistAddedRegion(region)

    /** Removes a region from Device.knownRegions (from the manage-regions screen). */
    suspend fun removeRegion(region: String) {
        dependencies.removeKnownRegion(region)
        state.update { current ->
            current.copy(knownRegions = current.knownRegions.filterNot { it == region })
        }
    }

    private suspend fun persistAddedRegion(region: String) {
        dependencies.addKnownRegion(region)
        state.update { current ->
            current.copy(knownRegions = (current.knownRegions + region).distinct().sorted())
        }
    }

    /** Runs nearby-region discovery for the embedded manage-regions screen, returning the raw result. */
    suspend fun runRegionDiscovery(): RegionDiscoverUiResult =
        dependencies.discoverNearbyRegions?.invoke() ?: RegionDiscoverUiResult.SendFailed

    /** Localized status copy for a discovery result (for the embedded manage-regions screen). */
    suspend fun regionDiscoverMessage(result: RegionDiscoverUiResult): String? =
        dependencies.regionDiscoverMessage(result)
}
