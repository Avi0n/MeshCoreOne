// AndroidOnly: WP-502 Self-authored schematic raster/metadata fixtures exercise actual optional-content UI without network or map-data claims.
// ReferenceInput: MC1/Views/Chats/Components/InlineImageView.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Components/Fragments/MapPreviewFragmentView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.container.AppChatMapPreviews
import com.meshcoreone.android.app.navigation.AppChatMapPreview
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppMapStrings as M
import com.meshcoreone.android.core.maps.MapSnapshotRenderer
import com.meshcoreone.android.core.maps.MapSnapshotRequest
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.feature.chats.composer.InlineImageSource
import com.meshcoreone.android.feature.chats.composer.LinkPreviewCardState
import com.meshcoreone.android.feature.chats.composer.LinkPreviewData
import com.meshcoreone.android.feature.chats.composer.ui.InlineImage
import com.meshcoreone.android.feature.chats.composer.ui.LinkPreviewCard
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*

class LocalContentCases(
    private val compose: ComposeTestRule,
    private val activity: Activity,
    private val setContent: (@Composable () -> Unit) -> Unit,
    private val device: Boolean = false,
) {
    private val capture = ConversationScreenCases(compose, activity, setContent, device)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun mount(content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
              MeshCoreTheme(motionScale = 0f) {
                Surface(if (device) Modifier.windowInsetsPadding(WindowInsets.systemBars) else Modifier) {
                    Column(Modifier.width(320.dp).height(600.dp).testTag("wp502-local-content")) {
                        Text("Self-authored local fixture: \u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd")
                        content()
                    }
                  }
                }
            }
        }
    }

    fun imageFailureRetryViewerAndPreviewSafety(directory: File) {
        val bitmap = schematicRaster()
        val image = bitmap.asImageBitmap()
        val failure = IOException("Synthetic local image decode failure")
        val reported = mutableListOf<Throwable>()
        var loads = 0
        val source = InlineImageSource { url ->
            assertEquals("fixture://wp502/image", url)
            if (++loads == 1) throw failure
            image
        }
        val url = "https://fixture.invalid/synthetic-page"
        val preview = mutableStateOf<LinkPreviewCardState>(LinkPreviewCardState.TapToLoad(url))
        var manualLoads = 0
        val opened = mutableListOf<String>()
        try {
            mount {
                InlineImage("fixture://wp502/image", 2.0, source, onError = { _, error -> reported += error })
                LinkPreviewCard(preview.value, source, opened::add, { manualLoads++ })
            }
            val failed = activity.getString(C.chatsInlineImageFailedLabel)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(failed).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(failed).assertIsDisplayed().performClick()
            val imageLabel = activity.getString(C.chatsInlineImageImageAccessibility)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(imageLabel).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, loads)
            assertSame(failure, reported.single())
            compose.onNodeWithContentDescription(imageLabel).performClick()
            compose.onNodeWithText(activity.getString(C.chatsImageViewerClose)).assertIsDisplayed().performClick()
            compose.onAllNodesWithContentDescription(imageLabel).assertCountEquals(1)
            compose.onNodeWithContentDescription(C.chatsPreviewTapAccessibility(activity.resources, "fixture.invalid")).performClick()
            assertEquals(1, manualLoads)
            val title = "Original cached title: \u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd"
            compose.runOnIdle { preview.value = LinkPreviewCardState.Loaded(LinkPreviewData(url, title)) }
            compose.onNodeWithContentDescription(
                C.chatsLinkPreviewAccessibilityLabel(activity.resources, title, "fixture.invalid"),
            ).assertIsDisplayed().performClick()
            assertEquals(listOf(url), opened)
            capture.captureMountedScreen("local-image-preview", directory, "wp502-local-content")
            compose.runOnIdle { preview.value = LinkPreviewCardState.Malware(url) }
            compose.onNodeWithContentDescription(C.chatsMalwareWarningAccessibility(activity.resources, "fixture.invalid"))
                .assertIsDisplayed().assertHasNoClickAction()
            assertEquals(listOf(url), opened)
        } finally {
            setContent {}
            compose.waitForIdle()
            bitmap.recycle()
        }
    }

    fun offlineMapFailureRetryAndCoordinateAction(directory: File) {
        val bitmap = schematicRaster()
        val pending = CompletableDeferred<Bitmap?>()
        val requests = mutableListOf<MapSnapshotRequest>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val previews = AppChatMapPreviews(activity, scope, object : MapSnapshotRenderer<Bitmap> {
            override suspend fun render(request: MapSnapshotRequest): Bitmap? {
                requests += request
                return if (requests.size == 1) pending.await() else bitmap
            }
        }, online = { false })
        val routes = mutableListOf<String>()
        try {
            mount { AppChatMapPreview(previews, Coordinate(10.0, 20.0), routes::add) }
            compose.onNodeWithTag("chat-map-loading", useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle { pending.complete(null) }
            val retry = activity.getString(M.mapPreviewRetryButtonAccessibilityLabel)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(retry, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(activity.getString(M.mapPreviewAccessibilityLabel)).assertIsDisplayed().performClick()
            val route = Uri.parse(routes.single())
            assertEquals("meshcore", route.scheme)
            assertEquals("map", route.host)
            assertEquals(10.0, requireNotNull(route.getQueryParameter("lat")).toDouble(), 0.0)
            assertEquals(20.0, requireNotNull(route.getQueryParameter("lon")).toDouble(), 0.0)
            compose.onNodeWithContentDescription(retry, useUnmergedTree = true)
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat-map-pin", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.latitude == 10.0 && it.longitude == 20.0 && it.isOffline })
            capture.captureMountedScreen("local-offline-schematic-map", directory, "wp502-local-content")
        } finally {
            setContent {}
            compose.waitForIdle()
            previews.close()
            scope.cancel()
            bitmap.recycle()
        }
    }

    private fun schematicRaster() = Bitmap.createBitmap(250, 150, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) {
            setPixel(x, y, 0xff000000.toInt() or ((x * 7 % 256) shl 16) or ((y * 13 % 256) shl 8) or ((x + y * 3) % 256))
        }
    }
}
