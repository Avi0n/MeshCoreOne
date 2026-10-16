package one.meshcore.shared.contact

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Discover's Distance sort reads the injected user location at each use. Without a location the order
 * falls back to Last Heard, so a screen that never receives the provider silently loses both the
 * distance ordering and the row's "X away" label. Mirrors iOS `DiscoveryView` / `bestAvailableLocation`.
 */
class DiscoveryViewModelLocationTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    private fun node(name: String, latitude: Double, lastHeard: Long) = DiscoveredNodeRecord(
        id = newDiscoveredNodeId(),
        radioID = radio,
        publicKey = ByteArray(PUBLIC_KEY_BYTES) { name.first().code.toByte() },
        name = name,
        typeRawValue = ContactType.REPEATER.raw,
        lastHeardEpochSeconds = lastHeard,
        latitude = latitude,
        longitude = 0.0,
    )

    private fun TestScope.viewModel(location: UserLocation?) = DiscoveryViewModel(
        scope = backgroundScope,
        dependencies = DiscoveryViewModel.Dependencies(
            store = { null },
            contactService = { null },
            radioId = { radio },
            userLocation = { location },
            loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
            errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
        ),
    )

    /** "Near" is closer but older, so only a wired location can put it first. */
    private val state = DiscoveryState(
        nodes = listOf(
            node("Far", latitude = 1.0, lastHeard = 30),
            node("Near", latitude = 0.01, lastHeard = 10),
        ),
        sortOrder = NodeSortOrder.DISTANCE,
    )

    @Test
    fun distanceSortOrdersByTheInjectedLocation() = runTest {
        val sorted = viewModel(UserLocation(latitude = 0.0, longitude = 0.0)).filteredNodes(state)

        assertEquals(listOf("Near", "Far"), sorted.map { it.name })
    }

    @Test
    fun withoutALocationDistanceSortFallsBackToLastHeard() = runTest {
        val sorted = viewModel(location = null).filteredNodes(state)

        assertEquals(listOf("Far", "Near"), sorted.map { it.name })
    }

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
    }
}
