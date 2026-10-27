// PortedFrom: MC1/Views/Chats/Components/Fragments/MapPreviewFragmentView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/MapPreviewSettingsSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppState/NavigationCoordinatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.content.DataStoreLinkPreviewPreferencesSource
import com.meshcoreone.android.app.navigation.AppMapSettings
import com.meshcoreone.android.app.navigation.AppMessageContent
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.navigation.chatMapPreviewCoordinate
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppMapStrings as M
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.maps.MapSnapshotRenderer
import com.meshcoreone.android.core.maps.MapSnapshotRequest
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31, 37], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMapPreviewTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>

    @Before fun attachNativeHost() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        controller.get().actionBar?.hide()
    }

    @After fun disposeNativeHost() { controller.pause().stop().destroy() }

    @Test fun mountedConversationShowsTheCoordinateCardWithWebPreviewsDisabledAndRoutesToMaps() = runTest {
        assertMountedCoordinateRoutesToMaps(37.7749, -122.4194)
    }

    @Test fun mountedConversationRoutesTheOriginalLondonThumbnailCoordinateToMaps() = runTest {
        assertMountedCoordinateRoutesToMaps(51.5074, -0.1278)
    }

    private suspend fun TestScope.assertMountedCoordinateRoutesToMaps(latitude: Double, longitude: Double) {
        withFixture {
            h.manager.connect(h.target())
            h.eventually {
                h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val peer = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 4 }),
                name = "Synthetic map peer", lastHeardTimestamp = null)
            store.saveContact(peer)
            store.saveMessage(message.copy(radioId = radio, contactID = peer.id,
                text = "$latitude, $longitude"))
            compose.runOnUiThread {
                h.container.navigation.navigateToChat(peer)
                host.setContent { MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature)
                } }
            }
            compose.waitUntil(10_000) {
                h.settle()
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
                .assertWidthIsAtLeast(250.dp).assertHeightIsAtLeast(150.dp)
            compose.onNodeWithTag("chat-map-pin", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(1, requests.size)
            assertEquals(latitude, requests.single().latitude, 0.0)
            assertEquals(longitude, requests.single().longitude, 0.0)
            assertFalse(storage.preferences.get(AppStorageKey.linkPreviewsEnabled))
            h.container.navigation.navigateToMap(1.0, 2.0)
            h.container.navigation.selectTab(AppTab.CHATS)
            compose.onNodeWithContentDescription(label).performClick()
            assertEquals(AppTab.MAP, h.container.navigation.state.value.selectedTab)
            assertEquals(
                com.meshcoreone.android.app.navigation.MapFocusRequest(latitude, longitude),
                h.container.navigation.state.value.pendingMapFocus,
            )
        }
    }

    @Test fun disabledThumbnailsAndCoordinatesInsideContactChipsNeverRequestTiles() = runTest {
        withFixture {
            storage.preferences.set(AppStorageKey.showMapPreviewThumbnails, false)
            mount(message)
            compose.waitForIdle()
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
            assertTrue(requests.isEmpty())
            val chip = "<${"AB".repeat(32)}:1:Base 37.7749, -122.4194>"
            compose.runOnUiThread { text.value = message.copy(text = chip) }
            compose.waitForIdle()
            storage.preferences.set(AppStorageKey.showMapPreviewThumbnails, true)
            compose.waitForIdle()
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
            assertTrue(requests.isEmpty())
            assertNull(chatMapPreviewCoordinate(chip, true))
            compose.runOnUiThread { text.value = message.copy(text = "$chip then 10.0, 20.0 and 30.0, 40.0") }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("chat-map-pin", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    requests.isNotEmpty()
            }
            assertEquals(1, requests.size)
            assertEquals(10.0, requests.single().latitude, 0.0)
            assertEquals(20.0, requests.single().longitude, 0.0)
        }
    }

    @Test fun sharedLoadingFailureAndRetryReachBothCardsWithoutDuplicateRenders() = runTest {
        val first = CompletableDeferred<Bitmap?>()
        val second = CompletableDeferred<Bitmap?>()
        var calls = 0
        withFixture(render = { if (++calls == 1) first.await() else second.await() }) {
            compose.runOnUiThread { host.setContent { MeshCoreTheme(motionScale = 0f) { Column {
                repeat(2) { AppMessageContent(content, message, ::report, onMap = {}) }
            } } } }
            compose.onAllNodesWithTag("chat-map-loading", useUnmergedTree = true).assertCountEquals(2)
            compose.onNodeWithContentDescription(retry, useUnmergedTree = true).assertDoesNotExist()
            assertEquals(1, calls)
            compose.runOnIdle { first.complete(null) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(retry, useUnmergedTree = true).fetchSemanticsNodes().size == 2
            }
            compose.onAllNodesWithContentDescription(retry, useUnmergedTree = true)[0]
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.onAllNodesWithTag("chat-map-loading", useUnmergedTree = true).assertCountEquals(2)
            assertEquals(2, calls)
            compose.runOnIdle { second.complete(bitmap()) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("chat-map-pin", useUnmergedTree = true).fetchSemanticsNodes().size == 2
            }
            compose.onAllNodesWithContentDescription(retry, useUnmergedTree = true).assertCountEquals(0)
            assertEquals(2, requests.size)
        }
    }

    @Test fun screenReentryUsesTheProcessCacheAndMemoryTrimRefreshesVisibleCards() = runTest {
        withFixture {
            val visible = mutableStateOf(true)
            compose.runOnUiThread { host.setContent { MeshCoreTheme(motionScale = 0f) {
                if (visible.value) AppMessageContent(content, message, ::report, onMap = {})
            } } }
            compose.onNodeWithTag("chat-map-pin", useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle { visible.value = false }
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
            compose.runOnIdle { visible.value = true }
            compose.onNodeWithTag("chat-map-pin", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(1, requests.size)
            compose.runOnIdle { content.trimMemory() }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("chat-map-pin", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    requests.size == 2
            }
            compose.onNodeWithTag("chat-map-pin", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun offlineFallbackRecoversToTheOnlineDarkKeyWithoutGatingCoordinateNavigation() = runTest {
        var online = false
        var opened: String? = null
        withFixture(online = { online }, render = { if (it.isOffline) null else bitmap() }) {
            mount(message, dark = true, onMap = { opened = it })
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(retry, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(requests.single().isOffline)
            assertFalse(requests.single().isDark)
            compose.onNodeWithContentDescription(label).performClick()
            assertEquals("meshcore://map?lat=37.774900&lon=-122.419400", opened)
            compose.runOnIdle { online = true; previews.refreshNetwork() }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("chat-map-pin", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(2, requests.size)
            assertFalse(requests.last().isOffline)
            assertTrue(requests.last().isDark)
        }
    }

    @Test fun memoryTrimDuringRenderingNotifiesTheLoadingCardAndDeduplicatesItsReplacement() = runTest {
        val first = CompletableDeferred<Bitmap?>()
        var calls = 0
        withFixture(render = { if (++calls == 1) first.await() else bitmap() }) {
            mount(message)
            compose.onNodeWithTag("chat-map-loading", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(1, requests.size)
            compose.runOnIdle { content.trimMemory() }
            compose.onNodeWithTag("chat-map-loading", useUnmergedTree = true).assertIsDisplayed()
            assertFalse(previews.snapshots.isResolved(requests.single()))
            compose.runOnIdle { first.complete(bitmap()) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("chat-map-pin", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    requests.size == 2
            }
            assertEquals(requests.first(), requests.last())
            compose.onNodeWithTag("chat-map-loading", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun queuedRendersCheckPersistedPrivacyBeforeInvokingTheTileRenderer() = runTest {
        val first = CompletableDeferred<Bitmap?>()
        var calls = 0
        withFixture(render = { if (++calls == 1) first.await() else bitmap() }) {
            mount(message.copy(text = "Synthetic message without coordinates"))
            val active = MapSnapshotRequest(1.0, 2.0, false, false)
            val queued = MapSnapshotRequest(3.0, 4.0, false, false)
            compose.runOnIdle { previews.request(active); previews.request(queued) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
                requests.isNotEmpty()
            }
            assertEquals(listOf(active), requests)
            storage.preferences.set(AppStorageKey.showMapPreviewThumbnails, false)
            compose.runOnIdle { first.complete(bitmap()) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
                previews.snapshots.isResolved(queued)
            }
            assertNull(previews.snapshots.value(queued))
            assertEquals(listOf(active), requests)
            storage.preferences.set(AppStorageKey.showMapPreviewThumbnails, true)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
                content.rendering.value[AppStorageKey.showMapPreviewThumbnails] &&
                    !previews.snapshots.isResolved(queued)
            }
            compose.runOnIdle { previews.request(queued) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
                previews.snapshots.value(queued) != null
            }
            assertEquals(listOf(active, queued), requests)
        }
    }

    @Test fun rendererExceptionIsARecoverableFallbackRatherThanAStuckSpinner() = runTest {
        var calls = 0
        withFixture(render = { if (++calls == 1) throw IllegalStateException("Synthetic renderer failure") else bitmap() }) {
            mount(message)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(retry, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("chat-map-loading", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithContentDescription(retry, useUnmergedTree = true).performClick()
            compose.onNodeWithTag("chat-map-pin", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(2, requests.size)
        }
    }

    @Test fun settingsTogglePersistsTheExistingIndependentPrivacyPreference() = runTest {
        withFixture {
            val settings = AppSettingsFeature.create(h.container, storage)
            val toggle = context.getString(S.mapPreviewsToggle)
            compose.runOnUiThread { host.setContent { MeshCoreTheme(motionScale = 0f) { AppMapSettings(settings) {} } } }
            compose.onNodeWithContentDescription(toggle).assertIsOn().performClick()
            val observations = mutableListOf<String>()
            fun jobState(owner: CoroutineScope) = owner.coroutineContext[Job]?.let {
                "active=${it.isActive},cancelled=${it.isCancelled},completed=${it.isCompleted}"
            } ?: "MISSING_JOB"
            try {
                compose.waitUntil(10_000) {
                    h.settle()
                    val stored = runBlocking { storage.preferences.get(AppStorageKey.showMapPreviewThumbnails) }
                    val observation = "poll=${observations.size + 1},sdk=${Build.VERSION.SDK_INT}," +
                        "stored=$stored,feature=${settings.preferenceValues.value[AppStorageKey.showMapPreviewThumbnails]}," +
                        "failure=${settings.failure.value},uiJob={${jobState(h.container.uiScope)}}," +
                        "fixtureJob={${jobState(scope)}},lifecycle=${host.lifecycle.currentState}," +
                        "destroyed=${host.isDestroyed},finishing=${host.isFinishing}"
                    observations += "$observation,offCount=${if (stored) "NOT_QUERIED" else "QUERY_PENDING"}"
                    !stored &&
                        compose.onAllNodes(hasContentDescription(toggle) and isOff()).fetchSemanticsNodes().size.let {
                            observations[observations.lastIndex] = "$observation,offCount=$it"
                            it == 1
                        }
                }
            } catch (failure: ComposeTimeoutException) {
                failure.addSuppressed(IllegalStateException(
                    "WP-317 privacy wait observations (sequential, not atomic):\n" +
                        observations.joinToString("\n"),
                ))
                throw failure
            }
            compose.onNodeWithContentDescription(toggle).assertIsOff()
            assertFalse(storage.preferences.get(AppStorageKey.linkPreviewsEnabled))
            assertTrue(requests.isEmpty())
            compose.runOnUiThread { host.setContent {} }
            content.close()
            scope.cancel()
            storage.close()
            val reopened = MeshCoreStorage.get(context)
            try { assertFalse(reopened.preferences.get(AppStorageKey.showMapPreviewThumbnails)) }
            finally { reopened.close() }
        }
    }

    @Test fun renderDeadlineAndProcessCloseCancelOwnedWorkAndNeverInventAnImage() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var cancelled = 0
        val previews = AppChatMapPreviews(context, backgroundScope, object : MapSnapshotRenderer<Bitmap> {
            override suspend fun render(request: MapSnapshotRequest): Bitmap? = try { awaitCancellation() }
            finally { cancelled++ }
        }, online = { true })
        val request = MapSnapshotRequest(1.0, 2.0, false, false)
        try {
            previews.request(request)
            runCurrent()
            advanceTimeBy(29_999)
            runCurrent()
            assertFalse(previews.snapshots.isResolved(request))
            advanceTimeBy(1)
            runCurrent()
            assertTrue(previews.snapshots.isResolved(request))
            assertNull(previews.snapshots.value(request))
            assertEquals(1, cancelled)
            previews.retry(request)
            runCurrent()
            previews.close()
            runCurrent()
            assertEquals(2, cancelled)
            assertFalse(previews.snapshots.isResolved(request))
            try { previews.request(request); fail("A closed process cannot restart rendering") }
            catch (expected: IllegalStateException) { assertEquals("Chat map previews are closed", expected.message) }
        } finally { previews.close() }
    }

    private inner class Fixture(
        val context: Context,
        val host: ComponentActivity,
        val storage: MeshCoreStorage,
        val scope: CoroutineScope,
        val previews: AppChatMapPreviews,
        val content: AppChatContent,
        val h: ContainerHarness,
        val requests: MutableList<MapSnapshotRequest>,
    ) {
        val label = context.getString(M.mapPreviewAccessibilityLabel)
        val retry = context.getString(M.mapPreviewRetryButtonAccessibilityLabel)
        val message = MessageDTO(radioId = RadioId(UUID.randomUUID()), contactID = UUID.randomUUID(),
            text = "37.7749, -122.4194", timestamp = 1u, direction = MessageDirection.INCOMING)
        val text = mutableStateOf(message)
        fun report(operation: String, cause: Throwable?) { throw AssertionError(operation, cause) }
        fun mount(message: MessageDTO, dark: Boolean = false, onMap: (String) -> Unit = {}) {
            compose.runOnUiThread {
                text.value = message
                host.setContent { MeshCoreTheme(colorSchemePreference = if (dark) AppColorSchemePreference.DARK else AppColorSchemePreference.LIGHT,
                    motionScale = 0f) { AppMessageContent(content, text.value, ::report, onMap) } }
            }
        }
    }

    private suspend fun TestScope.withFixture(
        online: () -> Boolean = { true },
        render: suspend (MapSnapshotRequest) -> Bitmap? = { bitmap() },
        block: suspend Fixture.() -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requests = mutableListOf<MapSnapshotRequest>()
        val renderTile = render
        val previews = AppChatMapPreviews(context, scope, object : MapSnapshotRenderer<Bitmap> {
            override suspend fun render(request: MapSnapshotRequest) = renderTile(request.also(requests::add))
        }, online, enabled = { storage.preferences.get(AppStorageKey.showMapPreviewThumbnails) })
        val http = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt =
                error("Synthetic map tests must not fetch web/image/malware content")
        }
        storage.preferences.set(AppStorageKey.linkPreviewsEnabled, false)
        storage.preferences.set(AppStorageKey.showMapPreviewThumbnails, true)
        val content = AppChatContent(context, store, http,
            DataStoreLinkPreviewPreferencesSource.create(storage.preferences, scope), storage.preferences,
            storage.preferences.snapshot(), scope, previews)
        val h = ContainerHarness(this, store, chatContent = content)
        try { Fixture(context, controller.get(), storage, scope, previews, content, h, requests).block() }
        finally {
            compose.runOnUiThread { controller.get().setContent {} }
            h.container.close()
            content.close()
            scope.cancel()
            storage.close()
        }
    }

    private fun bitmap() = Bitmap.createBitmap(250, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
}
