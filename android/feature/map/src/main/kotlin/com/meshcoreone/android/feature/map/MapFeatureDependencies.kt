// AndroidOnly: WP-312 App-bound immutable map data seam; feature code never constructs persistence services.
package com.meshcoreone.android.feature.map

import com.meshcoreone.android.core.maps.ContactMapType
import com.meshcoreone.android.core.maps.MapLine
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.OfflineMapController
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed interface MapSelection {
    data class Contact(val contact: ContactDTO) : MapSelection
    data class Discovered(val node: DiscoveredNodeDTO) : MapSelection
}

interface MapFeatureActions {
    fun openContact(contact: ContactDTO)
    fun openChat(contact: ContactDTO)
    suspend fun addDiscovered(node: DiscoveredNodeDTO)
}

data class MapFeatureUpdate(val sourceId: String?)

data class MapFeatureMarker(
    val marker: MapMarker,
    val type: ContactMapType,
    val isFavorite: Boolean,
    val isDiscovered: Boolean,
    val selection: MapSelection? = null,
)

data class MapFeatureContent(
    val markers: List<MapFeatureMarker> = emptyList(),
    val lines: List<MapLine> = emptyList(),
)

fun interface MapFeatureDataSource {
    suspend fun load(): MapFeatureContent
}

data class MapFeatureDependencies(
    val dataSource: MapFeatureDataSource,
    val offlineMaps: OfflineMapController,
    val updates: Flow<MapFeatureUpdate> = emptyFlow(),
    val actions: MapFeatureActions? = null,
)
