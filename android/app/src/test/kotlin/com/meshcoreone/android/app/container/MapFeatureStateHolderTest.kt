// AndroidOnly: WP-312 Deterministic screen-owner concurrency and source action regressions in the declared App runner.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.maps.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.map.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapFeatureStateHolderTest {
    @Test fun burstUsesFirstFiftyMillisecondWindowAndFilterChangesDoNotFetch() = runTest {
        val updates = MutableSharedFlow<MapFeatureUpdate>(extraBufferCapacity = 20)
        var loads = 0
        val holder = MapFeatureStateHolder(deps({ loads++; MapFeatureContent() }, updates), backgroundScope, MapFilterState())
        runCurrent()
        assertEquals(1, loads)
        updates.emit(MapFeatureUpdate("radio"))
        runCurrent()
        advanceTimeBy(30)
        updates.emit(MapFeatureUpdate("radio"))
        runCurrent()
        advanceTimeBy(19)
        runCurrent()
        assertEquals(1, loads)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, loads)
        holder.setFilter(MapFilterState(favoritesOnly = true))
        assertEquals(2, loads)
        holder.close()
    }

    @Test fun changedRadioClearsImmediatelyAndAnOldNonCooperativeReadCannotOverwriteIt() = runTest {
        val updates = MutableSharedFlow<MapFeatureUpdate>(extraBufferCapacity = 20)
        val held = CompletableDeferred<Unit>()
        val old = featureMarker("old")
        val current = featureMarker("current")
        var calls = 0
        val holder = MapFeatureStateHolder(deps({
            if (++calls == 1) withContext(NonCancellable) { held.await(); MapFeatureContent(listOf(old)) }
            else MapFeatureContent(listOf(current))
        }, updates), backgroundScope, MapFilterState())
        runCurrent()
        updates.emit(MapFeatureUpdate("new-radio"))
        runCurrent()
        assertTrue(holder.state.value.visibleMarkers.isEmpty())
        advanceTimeBy(50)
        runCurrent()
        assertEquals(current, holder.state.value.visibleMarkers.single())
        held.complete(Unit)
        runCurrent()
        assertEquals(current, holder.state.value.visibleMarkers.single())
        holder.close()
    }

    @Test fun selectionRefreshesItsDtoAndClearsOnFilterRemovalAndDeletion() = runTest {
        var marker = featureMarker("before")
        var content = MapFeatureContent(listOf(marker))
        val holder = MapFeatureStateHolder(deps({ content }), backgroundScope, MapFilterState())
        runCurrent()
        holder.select(marker.marker.id)
        marker = marker.copy(marker = marker.marker.copy(label = "after"))
        content = MapFeatureContent(listOf(marker))
        holder.refresh()
        runCurrent()
        assertEquals("after", holder.state.value.selected!!.marker.label)
        holder.setFilter(MapFilterState(favoritesOnly = true))
        assertNull(holder.state.value.selected)
        holder.setFilter(MapFilterState())
        holder.select(marker.marker.id)
        content = MapFeatureContent()
        holder.refresh()
        runCurrent()
        assertNull(holder.state.value.selected)
        holder.close()
    }

    @Test fun closingTheScreenCancelsOwnedWorkAndRejectsALateNonCooperativeResult() = runTest {
        val held = CompletableDeferred<Unit>()
        val marker = featureMarker("late")
        val holder = MapFeatureStateHolder(deps({
            withContext(NonCancellable) { held.await(); MapFeatureContent(listOf(marker)) }
        }), backgroundScope, MapFilterState())
        runCurrent()
        holder.close()
        held.complete(Unit)
        runCurrent()
        assertTrue(holder.state.value.visibleMarkers.isEmpty())
    }

    @Test fun realSelectionPayloadActionsAndFailedReadRecoveryAreNotSuccessShaped() = runTest {
        val contact = contact("contact")
        val node = node("discovered")
        val markers = listOf(
            featureMarker("contact", contact.id).copy(selection = MapSelection.Contact(contact)),
            featureMarker("discovered", node.id).copy(isDiscovered = true, selection = MapSelection.Discovered(node)),
        )
        val failure = IllegalStateException("store read failed")
        var fail = true
        val opened = mutableListOf<ContactDTO>()
        val added = mutableListOf<DiscoveredNodeDTO>()
        val gate = CompletableDeferred<Unit>()
        val actions = object : MapFeatureActions {
            override fun openContact(contact: ContactDTO) { opened += contact }
            override fun openChat(contact: ContactDTO) { opened += contact }
            override suspend fun addDiscovered(node: DiscoveredNodeDTO) { added += node; gate.await() }
        }
        val holder = MapFeatureStateHolder(deps({
            if (fail) throw failure
            MapFeatureContent(markers)
        }).copy(actions = actions), backgroundScope, MapFilterState(showDiscovered = true))
        runCurrent()
        assertSame(failure, holder.state.value.error)
        assertFalse(holder.state.value.loading)
        fail = false
        holder.refresh()
        runCurrent()
        assertNull(holder.state.value.error)
        holder.select(contact.id)
        holder.openContact()
        holder.select(contact.id)
        holder.openChat()
        assertEquals(listOf(contact, contact), opened)
        holder.select(node.id)
        holder.addDiscovered()
        holder.addDiscovered()
        runCurrent()
        assertEquals(listOf(node), added)
        assertTrue(holder.state.value.adding)
        gate.complete(Unit)
        runCurrent()
        assertFalse(holder.state.value.adding)
        assertNull(holder.state.value.selected)
        holder.close()
    }

    private fun deps(
        load: suspend () -> MapFeatureContent,
        updates: kotlinx.coroutines.flow.Flow<MapFeatureUpdate> = kotlinx.coroutines.flow.emptyFlow(),
    ) = MapFeatureDependencies(
        MapFeatureDataSource { load() },
        OfflineMapController(UnavailableOfflineMapBackend, emptyMap(), { error("No download") }, { error("No download") }),
        updates,
    )

    private fun featureMarker(name: String, id: UUID = UUID.randomUUID()) =
        MapFeatureMarker(MapMarker(id, GeoPoint(1.0, 2.0), MapPinStyle.CONTACT_CHAT, name), ContactMapType.CHAT, false, false)

    private fun contact(name: String) = ContactDTO(
        radioId = RadioId(UUID(0, 1)), publicKey = Bytes(ByteArray(32) { 1 }),
        name = name, typeRawValue = 1u, lastHeardTimestamp = null,
    )
    private fun node(name: String) = DiscoveredNodeDTO(
        UUID.randomUUID(), RadioId(UUID(0, 1)), Bytes(ByteArray(32) { 2 }), name, 1u,
        Instant.EPOCH, 0u, 1.0, 2.0, 255u, Bytes.EMPTY, null, null,
    )
}
