package one.meshcore.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.PairingFailureKind
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_settings_device_selection_setup_message
import one.meshcore.shared.resources.android_settings_device_selection_setup_message_plural
import one.meshcore.shared.resources.onboarding_device_scan_error_connection_failed
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingStatusRowsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private var density = 0f
    private val typography = Typography(
        bodySmall = TextStyle(fontSize = STATUS_SIZE.sp, lineHeight = STATUS_LINE.sp),
        bodyLarge = TextStyle(fontSize = NAME_SIZE.sp, lineHeight = NAME_LINE.sp),
    )

    @Test
    fun transientPairingCopyUsesTheStatusRowInsetsAndTypography() {
        lateinit var message: String
        compose.setContent {
            density = LocalDensity.current.density
            message = stringResource(Res.string.onboarding_device_scan_error_connection_failed)
            MaterialTheme(typography = typography) {
                ConnectionAlertContent(
                    ConnectionAlert.Pairing("radio", PairingFailureKind.TRANSIENT),
                    InertConnectionActions(),
                    {},
                )
            }
        }
        assertStatus(message)
    }

    @Test
    fun setupCopyAndPluralAccessoryNamesShareTheInsetCard() {
        val plural = mutableStateOf(false)
        lateinit var message: String
        compose.setContent {
            density = LocalDensity.current.density
            message = stringResource(
                if (plural.value) {
                    Res.string.android_settings_device_selection_setup_message_plural
                } else {
                    Res.string.android_settings_device_selection_setup_message
                },
            )
            MaterialTheme(typography = typography) {
                SystemPairingSetupDialog(
                    if (plural.value) {
                        listOf(CompanionAssociation("a", FIRST), CompanionAssociation("b", SECOND))
                    } else {
                        listOf(CompanionAssociation("a", FIRST))
                    },
                    onForget = {},
                    onCancel = {},
                )
            }
        }
        assertStatus(message)
        compose.runOnIdle { plural.value = true }
        compose.waitForIdle()
        assertStatus(message)
        val names = listOf(FIRST, SECOND).map { label ->
            assertTypography(label, NAME_SIZE, NAME_LINE)
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        }
        val messageNode = compose.onNodeWithText(message, useUnmergedTree = true).fetchSemanticsNode()
        val card = ancestors(messageNode).first { candidate ->
            names.all { name -> ancestors(name).any { it.id == candidate.id } }
        }
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        assertEquals(dialog.left + INSET * density, card.boundsInRoot.left, PIXEL_TOLERANCE)
        assertEquals(dialog.right - INSET * density, card.boundsInRoot.right, PIXEL_TOLERANCE)
        names.forEach { name ->
            assertEquals(card.boundsInRoot.left + INSET * density, name.boundsInRoot.left, PIXEL_TOLERANCE)
        }
        assertEquals(
            ROW_HEIGHT * density,
            names.last().boundsInRoot.center.y - names.first().boundsInRoot.center.y,
            PIXEL_TOLERANCE,
        )
    }

    private fun assertStatus(message: String) {
        val text = compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Status text inset from window",
            dialog.left + INSET * 2 * density,
            text.boundsInRoot.left,
            PIXEL_TOLERANCE,
        )
        val row = ancestors(text).first { it.boundsInRoot.width > text.boundsInRoot.width }
        assertEquals(dialog.left + INSET * density, row.boundsInRoot.left, PIXEL_TOLERANCE)
        assertEquals(dialog.right - INSET * density, row.boundsInRoot.right, PIXEL_TOLERANCE)
        assertTrue(
            "Status row allows multiline growth",
            row.boundsInRoot.height >= ROW_HEIGHT * density - PIXEL_TOLERANCE,
        )
        assertTypography(message, STATUS_SIZE, STATUS_LINE)
    }

    private fun assertTypography(label: String, size: Int, line: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(size.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals(line.sp, layouts.single().layoutInput.style.lineHeight)
    }

    private fun ancestors(node: SemanticsNode): List<SemanticsNode> =
        generateSequence(node.parent) { it.parent }.toList()

    private companion object {
        const val FIRST = "Alpha Radio"
        const val SECOND = "Bravo Radio"
        const val INSET = 16f
        const val ROW_HEIGHT = 56f
        const val STATUS_SIZE = 19
        const val STATUS_LINE = 23
        const val NAME_SIZE = 17
        const val NAME_LINE = 22
        const val PIXEL_TOLERANCE = 1f
    }
}
