package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_refresh
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDisclosureInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun refreshInvokesReloadAndLoadingKeepsContentVisible() {
        val loading = mutableStateOf(false)
        var reloads = 0
        lateinit var refresh: String
        compose.setContent {
            MaterialTheme {
                refresh = stringResource(Res.string.remotenodes_remote_nodes_status_refresh)
                SettingsDisclosure(
                    expanded = true,
                    onExpandedChange = {},
                    isRadioReady = true,
                    title = TITLE,
                    isLoading = loading.value,
                    onReload = { reloads++ },
                    footer = FOOTER,
                ) {
                    Box(Modifier.fillMaxWidth().height(BODY_HEIGHT).testTag(BODY))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(refresh).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, reloads)
            loading.value = true
        }
        compose.onNodeWithContentDescription(refresh).assertDoesNotExist()
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        compose.onNodeWithTag(BODY).assertIsDisplayed()
        compose.onNodeWithText(FOOTER).assertIsDisplayed()
        compose.runOnIdle { loading.value = false }
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
        compose.onNodeWithContentDescription(refresh).assertIsEnabled()
        compose.onNodeWithTag(BODY).assertIsDisplayed()
    }

    @Test
    fun noReloadOmitsRefreshAndNoFooterOmitsCaption() {
        lateinit var refresh: String
        compose.setContent {
            MaterialTheme {
                refresh = stringResource(Res.string.remotenodes_remote_nodes_status_refresh)
                SettingsDisclosure(expanded = true, onExpandedChange = {
                }, isRadioReady = true, title = TITLE, isLoading = false) {
                    Box(Modifier.fillMaxWidth().height(BODY_HEIGHT).testTag(BODY))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(refresh).assertDoesNotExist()
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
        compose.onNodeWithText(FOOTER).assertDoesNotExist()
        compose.onNodeWithTag(BODY).assertIsDisplayed()
    }

    @Test
    fun captionRendersBelowTheCardOnTheParentBackground() {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surfaceContainerLow = CARD_COLOR)) {
                Box(Modifier.fillMaxSize().background(PARENT_COLOR)) {
                    SettingsDisclosure(expanded = true, onExpandedChange = {
                    }, isRadioReady = true, title = TITLE, isLoading = false, footer = FOOTER) {
                        Box(Modifier.fillMaxWidth().height(BODY_HEIGHT).testTag(BODY))
                    }
                }
            }
        }
        val body = compose.onNodeWithTag(BODY).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithText(FOOTER).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Caption must be below the card content", footer.top >= body.bottom)
        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val pixels = root.captureToImage().toPixelMap()
        val x = (body.center.x - origin.x).toInt()
        val bodyY = (body.center.y - origin.y).toInt()
        val footerY = (footer.center.y - origin.y).toInt()
        // Short caption text leaves the center clear; the body verifies that the card is painted.
        assertEquals(CARD_COLOR.toArgb(), pixels[x, bodyY].toArgb())
        assertEquals(PARENT_COLOR.toArgb(), pixels[x, footerY].toArgb())
    }

    private companion object {
        const val TITLE = "Section"
        const val FOOTER = "Caption"
        const val BODY = "body"
        val BODY_HEIGHT = 48.dp
        val CARD_COLOR = Color.Green
        val PARENT_COLOR = Color.Magenta
    }
}
