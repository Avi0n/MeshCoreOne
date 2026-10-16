package one.meshcore.shared.pathedit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import one.meshcore.shared.pathedit.CodeInputResult
import one.meshcore.shared.pathedit.HopCodeClassification
import one.meshcore.shared.pathedit.HopPickerSource
import one.meshcore.shared.pathedit.PathEditState
import one.meshcore.shared.pathedit.PickerNode
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_path_edit_paste
import one.meshcore.shared.ui.HoldingClipboard
import one.meshcore.shared.ui.plainTextClipEntry
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HopPickerClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private lateinit var clipboard: HoldingClipboard
    private lateinit var source: RecordingPicker
    private lateinit var pasteLabel: String

    @After
    fun releaseWrite() {
        if (::clipboard.isInitialized) clipboard.release.complete(Unit)
    }

    @Test
    fun pasteTrimsABulkCodeAndAdoptsItsWidthAfterTheRead() {
        show(readEntry = plainTextClipEntry(PADDED_BULK))
        clickPaste()
        compose.runOnIdle {
            assertEquals(1, clipboard.reads)
            assertEquals(0, clipboard.completed)
            assertTrue(source.adopted.isEmpty())
        }
        compose.onNodeWithText(BULK).assertDoesNotExist()
        release()
        compose.onNodeWithText(BULK).assertExists()
        compose.runOnIdle { assertEquals(listOf(BULK), source.adopted) }
    }

    @Test
    fun pasteOfASingleCodeDoesNotAdoptAHashSize() {
        show(readEntry = plainTextClipEntry(SINGLE))
        clickPaste()
        release()
        compose.onNodeWithText(SINGLE).assertExists()
        compose.runOnIdle { assertTrue(source.adopted.isEmpty()) }
    }

    @Test
    fun blankClipboardLeavesTheSearchEmpty() {
        show(readEntry = null)
        clickPaste()
        release()
        compose.runOnIdle {
            assertEquals(1, clipboard.reads)
            assertTrue(source.adopted.isEmpty())
        }
        compose.onNodeWithText(BULK).assertDoesNotExist()
        clipboard.readEntry = plainTextClipEntry(BLANK)
        clickPaste()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, clipboard.reads)
            assertTrue(source.adopted.isEmpty())
        }
        compose.onNodeWithText(BULK).assertDoesNotExist()
    }

    @Test
    fun leavingThePickerCancelsAPendingPaste() {
        show(readEntry = plainTextClipEntry(BULK))
        clickPaste()
        compose.runOnIdle { assertEquals(1, clipboard.reads) }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertTrue(source.adopted.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, clipboard.completed)
            assertTrue(source.adopted.isEmpty())
        }
    }

    private fun show(readEntry: ClipEntry?) {
        clipboard = HoldingClipboard().also {
            it.readsAllowed = true
            it.readEntry = readEntry
        }
        source = RecordingPicker()
        compose.setContent {
            pasteLabel = stringResource(Res.string.contacts_path_edit_paste)
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MaterialTheme {
                    if (visible.value) {
                        AddHopPickerScreen(source = source, onBack = {})
                    }
                }
            }
        }
    }

    private fun clickPaste() {
        compose.onNodeWithContentDescription(pasteLabel).performClick()
    }

    private fun release() {
        compose.runOnIdle { clipboard.release.complete(Unit) }
        compose.waitForIdle()
    }

    private companion object {
        const val PADDED_BULK = "  A3,12  "
        const val BULK = "A3,12"
        const val SINGLE = "A3FF"
        const val BLANK = "   "
    }
}

private class RecordingPicker : HopPickerSource {
    override val state = MutableStateFlow(PathEditState())
    override val hopLimit: Int? = null
    val adopted = mutableListOf<String>()

    override fun appendHop(node: PickerNode) = Unit

    override fun addCodes(input: String) = CodeInputResult()

    override fun classifyCodes(input: String): List<HopCodeClassification> = emptyList()

    override fun adoptHashSize(pastedCodes: String) {
        adopted += pastedCodes
    }
}
