package one.meshcore.shared.chat.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.chat.ComposerState
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_input_accessibility_label
import one.meshcore.shared.resources.chats_input_encrypted
import one.meshcore.shared.resources.chats_input_not_encrypted
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ComposerAccessibilityInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val text = mutableStateOf(DRAFT)
    private val encrypted = mutableStateOf(true)
    private lateinit var composeView: View
    private lateinit var label: String
    private var sends = 0

    @Test
    fun nativeCharacterTraversalSelectsTheDraft() {
        showComposer()
        val field = compose.onNode(hasSetTextAction()).performClick()
        val id = field.fetchSemanticsNode().id
        compose.runOnIdle {
            val provider = requireNotNull(composeView.accessibilityNodeProvider)
            val info = requireNotNull(provider.createAccessibilityNodeInfo(id))
            assertEquals(DRAFT, info.text.toString())
            assertTrue(info.isEditable)
            assertTrue(
                provider.performAction(
                    id,
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 0)
                    },
                ),
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val provider = requireNotNull(composeView.accessibilityNodeProvider)
            assertTrue(
                provider.performAction(
                    id,
                    AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
                    Bundle().apply {
                        putInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER,
                        )
                        putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, true)
                    },
                ),
            )
        }
        compose.waitForIdle()
        assertEquals(TextRange(0, 1), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.runOnIdle {
            assertEquals(DRAFT, text.value)
            assertEquals(0, sends)
        }
    }

    @Test
    fun draftEditsEncryptionChangesAndClearKeepNativeTextSeparate() {
        showComposer()
        assertNativeFieldAndLabel()
        val id = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        compose.runOnIdle {
            assertTrue(
                requireNotNull(composeView.accessibilityNodeProvider).performAction(
                    id,
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, EDITED)
                    },
                ),
            )
            encrypted.value = false
        }
        compose.waitForIdle()
        assertNativeFieldAndLabel()
        compose.runOnIdle {
            assertEquals(EDITED, text.value)
            assertTrue(
                requireNotNull(composeView.accessibilityNodeProvider).performAction(
                    id,
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") },
                ),
            )
        }
        compose.waitForIdle()
        assertNativeFieldAndLabel()
        compose.runOnIdle {
            assertEquals("", text.value)
            assertEquals(0, sends)
        }
    }

    @Test
    fun aLargeFontKeepsTheLabelVisibleWithinTheComposer() {
        showComposer(fontScale = LARGE_FONT_SCALE)
        assertNativeFieldAndLabel()
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val labelBounds = compose.onNodeWithText(
            label,
            useUnmergedTree = true,
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(labelBounds.left >= bounds.left && labelBounds.right <= bounds.right)
        assertTrue(labelBounds.top >= bounds.top && labelBounds.bottom <= bounds.bottom)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, SCREENSHOT)
        FileOutputStream(file).use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun assertNativeFieldAndLabel() {
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        val field = compose.onNode(hasSetTextAction()).assertIsDisplayed().fetchSemanticsNode()
        val labelNode = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        assertNull(field.config.getOrNull(SemanticsProperties.ContentDescription))
        compose.runOnIdle {
            val provider = requireNotNull(composeView.accessibilityNodeProvider)
            val info = requireNotNull(provider.createAccessibilityNodeInfo(field.id))
            assertEquals(text.value, info.text?.toString().orEmpty())
            assertEquals("android.widget.EditText", info.className.toString())
            assertTrue(info.isEditable)
            assertNull(info.contentDescription)
            val labelInfo = requireNotNull(provider.createAccessibilityNodeInfo(labelNode.id))
            assertEquals(label, labelInfo.text.toString())
            assertTrue(labelInfo.isVisibleToUser)
        }
    }

    private fun showComposer(fontScale: Float = 1f) {
        compose.setContent {
            composeView = LocalView.current
            val density = LocalDensity.current.density
            label = composerAccessibilityLabel(
                stringResource(Res.string.chats_input_accessibility_label),
                stringResource(
                    if (encrypted.value) Res.string.chats_input_encrypted else Res.string.chats_input_not_encrypted,
                ),
            )
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        ChatInputBar(
                            state = ComposerState(text = text.value, maxBytes = MAX_BYTES),
                            isReady = false,
                            isEncrypted = encrypted.value,
                            placeholder = "Message",
                            onTextChange = { text.value = it },
                            onSend = { sends++ },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val DRAFT = "Proceed to the bridge"
        const val MAX_BYTES = 150
        const val EDITED = "Updated draft"
        const val LARGE_FONT_SCALE = 2f
        const val SCREENSHOT = "composer-label-font2.png"
    }
}
