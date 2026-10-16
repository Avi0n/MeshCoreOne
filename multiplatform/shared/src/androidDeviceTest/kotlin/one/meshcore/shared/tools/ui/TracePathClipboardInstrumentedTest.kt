package one.meshcore.shared.tools.ui

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.pathedit.PickerNode
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_trace_list_copy_path
import one.meshcore.shared.tools.TracePathViewModel
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.ui.HoldingClipboard
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TracePathClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val clipboard = HoldingClipboard()
    private val feedback = mutableListOf<HapticFeedbackType>()
    private val actions = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var copyLabel: String

    private val pathBuilder = TracePathBuilder(
        scope = scope,
        dependencies = TracePathBuilder.Dependencies(
            contactService = { null },
            radioId = { null },
        ),
    ).also { builder ->
        builder.appendHop(discoveredHop(FIRST_KEY, FIRST_ID))
        builder.appendHop(discoveredHop(SECOND_KEY, SECOND_ID))
    }

    private val tracedResult = TraceResult(
        hops = emptyList(),
        durationMs = RESULT_DURATION_MS,
        success = true,
        errorMessage = null,
        tracedPathBytes = byteArrayOf(TRACED_FIRST, TRACED_SECOND),
        hashSize = HASH_SIZE,
    )

    @After
    fun releaseWrite() {
        clipboard.release.complete(Unit)
        scope.cancel()
    }

    @Test
    fun outboundCopyIncludesAutoReturnAndConfirmsAfterCompletion() {
        show()
        assertEquals(MIRRORED_PATH, pathBuilder.fullPathString(autoReturnPath = true))
        copyAt(OUTBOUND_BUTTON)
        assertPending(MIRRORED_PATH)
        releaseAndAssertConfirmed(haptic = true)
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test
    fun outboundCopyWithoutAutoReturnOmitsTheMirror() {
        show(autoReturn = false)
        assertEquals(OUTBOUND_PATH, pathBuilder.fullPathString(autoReturnPath = false))
        copyAt(OUTBOUND_BUTTON)
        assertPending(OUTBOUND_PATH)
        releaseAndAssertConfirmed(haptic = true)
    }

    @Test
    fun resultCopyWritesTracedPathWithoutHaptic() {
        show(result = tracedResult)
        compose.onAllNodesWithText(copyLabel).assertCountEquals(COPY_BUTTON_COUNT)
        copyAt(RESULT_BUTTON)
        assertPending(TRACED_PATH)
        releaseAndAssertConfirmed(haptic = false)
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test
    fun removingListCancelsPendingOutboundCopy() {
        show()
        copyAt(OUTBOUND_BUTTON)
        assertPending(MIRRORED_PATH)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
        }
    }

    @Test
    fun failedResultAndEmptyPathHaveNoCopyButton() {
        show(withPath = false, result = tracedResult.copy(success = false, errorMessage = "Timed out"))
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(clipboard.entries.isEmpty())
            assertTrue(feedback.isEmpty())
            assertTrue(actions.isEmpty())
        }
    }

    private fun copyAt(index: Int) {
        compose.onAllNodesWithText(copyLabel)[index].performScrollTo().performClick()
    }

    private fun assertPending(expected: String) {
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(
                expected,
                requireNotNull(clipboard.entries.single()).clipData.getItemAt(0).text.toString(),
            )
            assertEquals(0, clipboard.completed)
            assertEquals(0, clipboard.cancelled)
            assertTrue(feedback.isEmpty())
        }
    }

    private fun releaseAndAssertConfirmed(haptic: Boolean) {
        compose.runOnIdle { clipboard.release.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.completed)
            assertEquals(0, clipboard.cancelled)
            assertEquals(
                if (haptic) listOf(HapticFeedbackType.Confirm) else emptyList(),
                feedback,
            )
        }
    }

    private fun show(autoReturn: Boolean = true, result: TraceResult? = null, withPath: Boolean = true) {
        val model = TracePathViewModel(
            scope,
            TracePathViewModel.Dependencies(
                runner = { null },
                builder = TracePathBuilder.Dependencies(contactService = { null }, radioId = { null }),
                deviceHashMode = { null },
                deviceLocation = { null },
                savedPathStore = null,
                messages = { TracePathViewModel.Messages(EMPTY_HINT, EMPTY_HINT, EMPTY_HINT, EMPTY_HINT) },
            ),
            initialState = TracePathViewModel.State(autoReturn = autoReturn, result = result),
        )
        val built = if (withPath) {
            pathBuilder
        } else {
            TracePathBuilder(
                scope = scope,
                dependencies = TracePathBuilder.Dependencies(
                    contactService = { null },
                    radioId = { null },
                ),
            )
        }
        compose.setContent {
            TimestampFormatting {
                val state = model.state.collectAsStateWithLifecycle()
                val ui = remember(model) { TracePathUiState(model, state) }
                copyLabel = stringResource(Res.string.contacts_trace_list_copy_path)
                CompositionLocalProvider(
                    LocalClipboard provides clipboard,
                    LocalHapticFeedback provides object : HapticFeedback {
                        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                            feedback += hapticFeedbackType
                        }
                    },
                ) {
                    MaterialTheme {
                        if (visible.value) {
                            TracePathListPane(
                                ui = ui,
                                scrollState = rememberScrollState(),
                                runTraceRequester = remember { BringIntoViewRequester() },
                                outboundPath = built.outboundPath,
                                builder = built,
                                emptyPathHint = EMPTY_HINT,
                                supportsHashOverride = false,
                                hashSize = HASH_SIZE,
                                canRunTrace = false,
                                isRadioReady = false,
                                onRun = { actions += "run" },
                                onCancel = { actions += "cancel" },
                                savedPathStore = null,
                                hasRadio = false,
                                canSave = false,
                                onSave = { actions += "save" },
                                matchingSaved = null,
                                resultMatchesPath = false,
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val OUTBOUND_BUTTON = 0
        const val RESULT_BUTTON = 1
        const val COPY_BUTTON_COUNT = 2
        const val HASH_SIZE = 1
        const val RESULT_DURATION_MS = 40
        const val FIRST_ID = "A0000000-0000-0000-0000-000000000001"
        const val SECOND_ID = "A0000000-0000-0000-0000-000000000002"
        const val OUTBOUND_PATH = "A3,12"
        const val MIRRORED_PATH = "A3,12,A3"
        const val TRACED_PATH = "BE,EF"
        const val EMPTY_HINT = "Empty"
        val FIRST_KEY = byteArrayOf(0xA3.toByte(), 0x01)
        val SECOND_KEY = byteArrayOf(0x12, 0x02)
        val TRACED_FIRST = 0xBE.toByte()
        val TRACED_SECOND = 0xEF.toByte()

        fun discoveredHop(key: ByteArray, id: String) = PickerNode.Discovered(
            DiscoveredNode(id = Uuid.normalized(id), publicKey = key, name = id),
        )
    }
}
