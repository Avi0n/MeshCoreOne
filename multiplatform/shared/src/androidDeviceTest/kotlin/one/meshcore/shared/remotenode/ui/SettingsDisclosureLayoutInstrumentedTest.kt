package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_parent
import one.meshcore.shared.settings.ui.RadioOptionPicker
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDisclosureLayoutInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pickerUsesTheCardWidthAndFieldsUseTheBodyInset() {
        var hubInset = 0f
        var bodyInset = 0f
        compose.setContent {
            hubInset = with(LocalDensity.current) { PreferenceHubHorizontalPadding.toPx() }
            bodyInset = with(LocalDensity.current) { SETTINGS_DISCLOSURE_BODY_PADDING.toPx() }
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    SettingsDisclosure(expanded = true, onExpandedChange = {
                    }, isRadioReady = true, title = TITLE, isLoading = false) {
                        RadioOptionPicker(
                            label = PICKER,
                            selectedLabel = VALUE,
                            selectedAccessibilityLabel = VALUE,
                            accessibilityHint = PICKER,
                            options = listOf(VALUE),
                            optionLabel = { it },
                            optionAccessibilityLabel = { it },
                            enabled = false,
                            onSelect = {},
                        )
                        SettingsDisclosureFields {
                            Box(Modifier.fillMaxWidth().height(FIELD_HEIGHT).testTag(FIELD))
                        }
                    }
                }
            }
        }
        val picker = compose.onNode(hasText(PICKER) and hasClickAction())
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag(FIELD).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertInsets(picker, field, hubInset, bodyInset)
    }

    @Test
    fun regionFormSharesTheSectionContainerWithoutExtraInsets() {
        var hubInset = 0f
        var bodyInset = 0f
        lateinit var title: String
        lateinit var parentLabel: String
        compose.setContent {
            hubInset = with(LocalDensity.current) { PreferenceHubHorizontalPadding.toPx() }
            bodyInset = with(LocalDensity.current) { SETTINGS_DISCLOSURE_BODY_PADDING.toPx() }
            title = stringResource(Res.string.remotenodes_remote_nodes_settings_regions)
            parentLabel = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_parent)
            val scope = rememberCoroutineScope()
            val viewModel = remember(scope) {
                NodeSettingsViewModel(
                    scope = scope,
                    sessionId = SESSION_ID,
                    dependencies = NodeSettingsViewModel.Dependencies(repeaterAdminService = { null }),
                )
            }
            DisposableEffect(viewModel) {
                onDispose { viewModel.cleanup() }
            }
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    NodeRegionsSection(
                        isRadioReady = true,
                        state = NodeSettingsState(regionsExpanded = true, regionsLoaded = true),
                        viewModel = viewModel,
                    )
                }
            }
        }
        compose.waitForIdle()
        val picker = compose.onNode(hasText(parentLabel) and hasClickAction())
            .assertIsDisplayed().fetchSemanticsNode()
        val field = compose.onNode(hasSetTextAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertInsets(picker.boundsInRoot, field, hubInset, bodyInset)
        val header = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
        val container = requireNotNull(picker.parent)
        assertTrue(
            "Parent picker must share the section header's container rather than a nested card",
            generateSequence(header) { it.parent }.any { it.id == container.id },
        )
    }

    private fun assertInsets(picker: Rect, field: Rect, hubInset: Float, bodyInset: Float) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("Picker left edge", root.left + hubInset, picker.left, PIXEL_TOLERANCE)
        assertEquals("Picker right edge", root.right - hubInset, picker.right, PIXEL_TOLERANCE)
        assertEquals("Field left edge", picker.left + bodyInset, field.left, PIXEL_TOLERANCE)
        assertEquals("Field right edge", picker.right - bodyInset, field.right, PIXEL_TOLERANCE)
    }

    private companion object {
        const val TITLE = "Section"
        const val PICKER = "Picker"
        const val VALUE = "Value"
        const val FIELD = "field"
        const val PIXEL_TOLERANCE = 1f
        val FIELD_HEIGHT = 48.dp
        val SESSION_ID = Uuid("00000000-0000-0000-0000-000000000001")
    }
}
