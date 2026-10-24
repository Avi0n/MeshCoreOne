// PortedFrom: MC1/Views/Map/MapViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Map/MapView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.map

import com.meshcoreone.android.core.maps.MapFilterState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MapFeatureState(
    val content: MapFeatureContent = MapFeatureContent(),
    val filter: MapFilterState = MapFilterState(),
    val selectedId: UUID? = null,
    val loading: Boolean = true,
    val error: Throwable? = null,
    val adding: Boolean = false,
) {
    val visibleMarkers: List<MapFeatureMarker> get() = content.markers.filter { item ->
        (!filter.favoritesOnly || item.isFavorite) &&
            (filter.favoritesOnly || !item.isDiscovered || filter.effectiveShowDiscovered) &&
            filter.allows(item.type)
    }
    val selected: MapFeatureMarker? get() = visibleMarkers.firstOrNull { it.marker.id == selectedId }
}

/** Screen-owned and main-confined; the process store and radio graph remain App-owned. */
class MapFeatureStateHolder(
    private val dependencies: MapFeatureDependencies,
    scope: CoroutineScope,
    filter: MapFilterState,
) {
    private val owner = Job(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + owner)
    private val mutableState = MutableStateFlow(MapFeatureState(filter = filter))
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var sourceId: String? = null
    private var pendingReload: Job? = null
    private var load: Job? = null

    fun close() { owner.cancel() }

    init {
        refresh()
        scope.launch {
            dependencies.updates.collect { update ->
                generation++
                load?.cancel()
                if (sourceId != update.sourceId) {
                    sourceId = update.sourceId
                    mutableState.update { it.copy(content = MapFeatureContent(), selectedId = null, loading = true) }
                }
                // First-event window, not a trailing debounce: a continuous stream cannot starve the map.
                if (pendingReload == null) {
                    pendingReload = scope.launch {
                        delay(50)
                        pendingReload = null
                        startLoad()
                    }
                }
            }
        }
    }

    fun setFilter(filter: MapFilterState) {
        mutableState.update { it.copy(filter = filter, selectedId = null) }
    }

    fun select(id: UUID?) {
        mutableState.update { state ->
            state.copy(selectedId = id?.takeIf { target -> state.visibleMarkers.any { it.marker.id == target } })
        }
    }

    fun refresh() {
        pendingReload?.cancel()
        pendingReload = null
        generation++
        load?.cancel()
        mutableState.update { it.copy(loading = true) }
        startLoad()
    }

    private fun startLoad() {
        val current = generation
        mutableState.update { it.copy(error = null) }
        load = scope.launch {
            try {
                val content = dependencies.dataSource.load()
                if (current == generation) mutableState.update {
                    val next = it.copy(content = content, loading = false)
                    next.copy(selectedId = next.selected?.marker?.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current == generation) mutableState.update { it.copy(loading = false, error = error) }
            }
        }
    }

    fun openContact() = act { selection ->
        if (selection is MapSelection.Contact) dependencies.actions?.openContact(selection.contact)
    }

    fun openChat() = act { selection ->
        if (selection is MapSelection.Contact) dependencies.actions?.openChat(selection.contact)
    }

    private fun act(action: (MapSelection) -> Unit) {
        val selection = state.value.selected?.selection ?: return
        try {
            action(selection)
            select(null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(error = error) }
        }
    }

    fun addDiscovered() {
        val node = (state.value.selected?.selection as? MapSelection.Discovered)?.node ?: return
        val actions = dependencies.actions ?: return
        if (state.value.adding) return
        mutableState.update { it.copy(adding = true, error = null) }
        val current = generation
        scope.launch {
            try {
                actions.addDiscovered(node)
                if (current == generation) {
                    select(null)
                    refresh()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current == generation) mutableState.update { it.copy(error = error) }
            } finally {
                mutableState.update { it.copy(adding = false) }
            }
        }
    }
}
