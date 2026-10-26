// PortedFrom: MC1Tests/State/PendingExternalURLTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Utilities/MeshCoreURLParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.meshcoreone.android.app.NavigationHostViewModel
import com.meshcoreone.android.app.deeplinks.AppDeepLinkRouter
import com.meshcoreone.android.app.deeplinks.ContainerDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.DeepLinkRouteOutcome
import com.meshcoreone.android.app.navigation.MapFocusRequest
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LinkConsumerIntegrationTest : RoomProcessTest() {
    private val sanFrancisco = "meshcore://map?lat=37.7749&lon=-122.4194"
    private val focus = MapFocusRequest(37.7749, -122.4194)

    @Test fun initialViewIntentIsHeldUntilActualContainerBinding() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = ContainerHarness(this, store)
        val host = NavigationHostViewModel()
        val retained = ViewModelStore().apply { put("navigation", host) }
        try {
            host.submitInitialViewIntent(Intent(Intent.ACTION_VIEW, Uri.parse(sanFrancisco)))
            testScheduler.runCurrent()
            assertNull(host.navigation.state.value.pendingMapFocus)
            assertEquals(sanFrancisco, host.pendingExternalRoute.snapshot()?.uri)
            host.bindDeepLinks(h.container)
            testScheduler.runCurrent()
            assertSame(h.container.navigation, host.navigation)
            assertEquals(focus, h.container.navigation.state.value.pendingMapFocus)
            assertEquals(AppTab.MAP, h.container.navigation.state.value.selectedTab)
            assertNull(host.pendingExternalRoute.snapshot())
        } finally {
            retained.clear()
            h.container.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun repeatedBindingAndReadinessCannotReplayTheConsumedHeldIntent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = ContainerHarness(this, store)
        val host = NavigationHostViewModel()
        val retained = ViewModelStore().apply { put("navigation", host) }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sanFrancisco))
            host.submitInitialViewIntent(intent)
            testScheduler.runCurrent()
            host.bindDeepLinks(h.container)
            testScheduler.runCurrent()
            assertEquals(focus, h.container.navigation.state.value.pendingMapFocus)
            h.container.navigation.clearPendingMapFocus()
            h.container.navigation.selectTab(AppTab.NODES)
            host.bindDeepLinks(h.container)
            host.pendingExternalRoute.markReady()
            host.submitInitialViewIntent(intent)
            testScheduler.runCurrent()
            assertNull(h.container.navigation.state.value.pendingMapFocus)
            assertEquals(AppTab.NODES, h.container.navigation.state.value.selectedTab)
            assertNull(host.pendingExternalRoute.snapshot())
        } finally {
            retained.clear()
            h.container.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun newViewIntentAfterActualBindingRoutesImmediatelyToTheOriginalCoordinate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = ContainerHarness(this, store)
        val host = NavigationHostViewModel()
        val retained = ViewModelStore().apply { put("navigation", host) }
        try {
            host.bindDeepLinks(h.container)
            testScheduler.runCurrent()
            host.submitNewViewIntent(Intent(Intent.ACTION_VIEW, Uri.parse(sanFrancisco)))
            testScheduler.runCurrent()
            assertEquals(focus, h.container.navigation.state.value.pendingMapFocus)
            assertEquals(AppTab.MAP, h.container.navigation.state.value.selectedTab)
            assertNull(host.pendingExternalRoute.snapshot())
        } finally {
            retained.clear()
            h.container.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun actualExternalAndChatProjectionKeepPrivateHashtagWarningAndOptionalScope() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val feature = h.container.chatsFeature
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, h.container.navigation))
            val chat = ChatLinkRouter(feature.list.links, backgroundScope)
            val secret = Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
            for ((name, region) in listOf("#test" to null, "#avion-testing2" to "testregion")) {
                val url = "meshcore://channel/add?name=%23${name.drop(1)}&secret=${secret.hexString}" +
                    (region?.let { "&region_scope=$it" } ?: "")
                assertEquals(DeepLinkRouteOutcome.ConfirmationStaged, external.routeExternal(url))
                val request = assertNotNull(h.container.navigation.state.value.pendingChannelLink)
                assertEquals(name, request.name)
                assertEquals(secret, request.secret)
                assertEquals(region, request.regionScope)
                assertTrue(request.hasHashtagSecretMismatch)
                h.container.navigation.clearPendingChannelLink()
                assertTrue(chat.route(url))
                testScheduler.runCurrent()
                assertEquals(request, h.container.navigation.state.value.pendingChannelLink)
                h.container.navigation.clearPendingChannelLink()
            }
            assertTrue(h.links.radios.isEmpty(), "Parsing and staging must not connect a radio")
        } finally { h.container.close() }
    }
}
