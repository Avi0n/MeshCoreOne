// AndroidOnly: WP-312 Mounted MapEntry action/filter/live-refresh and legal-link regressions.
// Native MapLibre is replaced only at the rendering seam; these are not device/projection observations.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.maps.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.map.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class MapMountedConsumerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val radio = RadioId(UUID(0, 1))
    private val contact = ContactDTO(
        id = UUID(0, 2), radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }),
        name = "Located contact", typeRawValue = 1u, latitude = 37.0, longitude = -122.0,
        lastHeardTimestamp = null,
    )
    private val node = DiscoveredNodeDTO(
        UUID(0, 3), radio, Bytes(ByteArray(32) { 2 }), "Located discovery", 1u,
        Instant.EPOCH, 0u, 38.0, -123.0, 255u, Bytes.EMPTY, null, null,
    )

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        host.get().getSharedPreferences("shared-map", 0).edit().clear().commit()
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun mountedContactDetailAndMessageForwardTheActualDtoAndClearSelection() {
        val details = mutableListOf<ContactDTO>()
        val messages = mutableListOf<ContactDTO>()
        mount(MapFeatureDependencies(source { content() }, offline(), actions = object : MapFeatureActions {
            override fun openContact(contact: ContactDTO) { details += contact }
            override fun openChat(contact: ContactDTO) { messages += contact }
            override suspend fun addDiscovered(node: DiscoveredNodeDTO) = error("No add requested")
        }))
        compose.onNodeWithTag("pick:${contact.id}").performClick()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_callout_details)).performClick()
        assertEquals(listOf(contact), details)
        compose.onNodeWithText("37.0, -122.0").assertDoesNotExist()
        compose.onNodeWithTag("pick:${contact.id}").performClick()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_callout_message)).performClick()
        assertEquals(listOf(contact), messages)
    }

    @Test fun mountedFilterAndLiveStoreRefreshCannotRetainAHiddenOrDeletedSelection() {
        val updates = MutableSharedFlow<MapFeatureUpdate>(extraBufferCapacity = 10)
        var current = content()
        mount(MapFeatureDependencies(source { current }, offline(), updates))
        compose.onNodeWithTag("pick:${contact.id}").performClick()
        compose.onNodeWithText("37.0, -122.0").assertExists()
        compose.onNodeWithText(text(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites)).performClick()
        compose.onNodeWithText("37.0, -122.0").assertDoesNotExist()
        compose.onNodeWithTag("pick:${contact.id}").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites)).performClick()
        compose.onNodeWithTag("pick:${contact.id}").performClick()
        compose.runOnIdle {
            current = MapFeatureContent()
            assertTrue(updates.tryEmit(MapFeatureUpdate("same-radio")))
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("37.0, -122.0").assertDoesNotExist()
    }

    @Test fun mountedDiscoveredDetailAddsTheActualNodeThroughTheAppPort() {
        val added = mutableListOf<DiscoveredNodeDTO>()
        mount(MapFeatureDependencies(source { content() }, offline(), actions = object : MapFeatureActions {
            override fun openContact(contact: ContactDTO) = error("No navigation")
            override fun openChat(contact: ContactDTO) = error("No navigation")
            override suspend fun addDiscovered(node: DiscoveredNodeDTO) { added += node }
        }))
        compose.onNodeWithText(text(R.string.l10n_app_map_map_callout_discovered)).performClick()
        compose.onNodeWithTag("pick:${node.id}").performClick()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_discovereddetail_title)).assertExists()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_discovereddetail_add)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(node), added)
    }

    @Test fun realSharedAttributionRendersAndDispatchesEveryApprovedProviderLink() {
        val opened = mutableListOf<String>()
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) {
                    MapAttribution(MapLibreOpenFreeMap.attribution)
                }
            }
        }
        MapLibreOpenFreeMap.attribution.forEach { provider ->
            compose.onNodeWithText(provider.label).assertIsDisplayed().performClick()
        }
        assertEquals(MapLibreOpenFreeMap.attribution.map { it.legalUri }, opened)
    }

    private fun mount(dependencies: MapFeatureDependencies) {
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                MapEntry(
                    FeatureRoute(FeatureId.MAP), { error("No generic navigation") }, dependencies,
                    mapSurface = { state, _, _, _, select ->
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.testTag("test-renderer")) {
                                state.points.forEach { marker ->
                                    TextButton({ select(marker) }, Modifier.testTag("pick:${marker.id}")) { Text(marker.label.orEmpty()) }
                                }
                            }
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun content() = MapFeatureContent(listOf(
        MapFeatureMarker(
            MapMarker(contact.id, GeoPoint(contact.latitude, contact.longitude), MapPinStyle.CONTACT_CHAT, contact.name),
            ContactMapType.CHAT, false, false, MapSelection.Contact(contact),
        ),
        MapFeatureMarker(
            MapMarker(node.id, GeoPoint(node.latitude, node.longitude), MapPinStyle.CONTACT_CHAT, node.name),
            ContactMapType.CHAT, false, true, MapSelection.Discovered(node),
        ),
    ))
    private fun source(load: suspend () -> MapFeatureContent) = MapFeatureDataSource { load() }
    private fun offline() = OfflineMapController(
        UnavailableOfflineMapBackend, emptyMap(), { error("No download") }, { error("No download") },
    )
    private fun text(id: Int) = host.get().getString(id)
}
