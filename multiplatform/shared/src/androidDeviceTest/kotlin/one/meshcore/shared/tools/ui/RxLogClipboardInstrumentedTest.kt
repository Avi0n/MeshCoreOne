package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_rx_log_copy
import one.meshcore.shared.resources.tools_rx_log_direct
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogPayloadType
import one.meshcore.shared.ui.HoldingClipboard
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RxLogClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val expanded = mutableStateOf(true)
    private val clipboard = HoldingClipboard()
    private val feedback = mutableListOf<HapticFeedbackType>()
    private lateinit var copyDescription: String
    private lateinit var directLabel: String

    @After
    fun releaseWrite() {
        compose.mainClock.autoAdvance = true
        clipboard.release.complete(Unit)
    }

    @Test
    fun rawCopyConfirmsAfterCompletionThenRestoresCopyTint() {
        showEntry()
        val copy = compose.onNodeWithContentDescription(copyDescription).performScrollTo()
        assertIconTint(Color.Blue)
        compose.mainClock.autoAdvance = false
        copy.performClick()
        compose.mainClock.advanceTimeBy(PENDING_INTERVAL_MILLIS)
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(COPIED_PAYLOAD, copiedText())
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
        }
        assertIconTint(Color.Blue)
        compose.runOnIdle { clipboard.release.complete(Unit) }
        compose.waitUntil(timeoutMillis = WRITE_SETTLE_TIMEOUT_MILLIS) {
            compose.runOnIdle { clipboard.completed == 1 }
        }
        compose.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
        }
        compose.mainClock.advanceTimeBy(COMPLETION_FRAMES_MILLIS)
        assertIconTint(Color.Red)
        compose.mainClock.advanceTimeBy(CONFIRMATION_SETTLE_MILLIS)
        assertIconTint(Color.Blue)
        compose.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
        }
    }

    @Test
    fun collapsingDetailCancelsPendingCopyAfterExitAnimation() {
        showEntry()
        copyPayload()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertTrue(feedback.isEmpty())
        }
        compose.onNodeWithText(directLabel).performScrollTo().performClick()
        compose.onNodeWithContentDescription(copyDescription).assertDoesNotExist()
        assertCancelled(listOf(HapticFeedbackType.SegmentTick))
    }

    @Test
    fun removingRowCancelsPendingCopyWithoutConfirmation() {
        showEntry()
        copyPayload()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertTrue(feedback.isEmpty())
            visible.value = false
        }
        compose.waitForIdle()
        assertCancelled(emptyList())
    }

    @Test
    fun emptyPayloadCopiesEmptyTextInsteadOfDisplayPlaceholder() {
        showEntry(ENTRY.copy(rawPayload = ByteArray(0)))
        compose.onNodeWithText("—").assertExists()
        copyPayload()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals("", copiedText())
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.completed)
            assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
        }
    }

    private fun copyPayload() {
        compose.onNodeWithContentDescription(copyDescription).performScrollTo().performClick()
    }

    private fun copiedText(): String = requireNotNull(clipboard.entries.single()).clipData.getItemAt(0).text.toString()

    private fun assertCancelled(expectedFeedback: List<HapticFeedbackType>) {
        compose.runOnIdle {
            assertEquals(1, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertEquals(expectedFeedback, feedback)
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, clipboard.completed)
            assertEquals(expectedFeedback, feedback)
        }
    }

    private fun assertIconTint(expected: Color) {
        val pixels = compose.onNodeWithContentDescription(copyDescription, useUnmergedTree = true)
            .captureToImage().toPixelMap()
        var matching = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                // State layers may dim an icon without changing its hue.
                val strength = maxOf(color.red, color.green, color.blue)
                if (strength < MINIMUM_COLOR_STRENGTH) continue
                if (kotlin.math.abs(color.red - strength * expected.red) <= COLOR_TOLERANCE &&
                    kotlin.math.abs(color.green - strength * expected.green) <= COLOR_TOLERANCE &&
                    kotlin.math.abs(color.blue - strength * expected.blue) <= COLOR_TOLERANCE
                ) {
                    matching++
                }
            }
        }
        assertTrue("Copy icon must contain $expected pixels", matching >= MINIMUM_TINT_PIXELS)
    }

    private fun showEntry(entry: RxLogEntry = ENTRY) {
        compose.setContent {
            TimestampFormatting {
                copyDescription = stringResource(Res.string.tools_rx_log_copy)
                directLabel = stringResource(Res.string.tools_rx_log_direct)
                CompositionLocalProvider(
                    LocalClipboard provides clipboard,
                    LocalHapticFeedback provides object : HapticFeedback {
                        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                            feedback += hapticFeedbackType
                        }
                    },
                ) {
                    MaterialTheme(colorScheme = lightColorScheme(primary = Color.Red, onSurfaceVariant = Color.Blue)) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            if (visible.value) {
                                RxLogExpandableRow(
                                    entry = entry,
                                    expanded = expanded.value,
                                    groupCount = 1,
                                    localPublicKeyPrefix = null,
                                    nodeNames = emptyMap(),
                                    onToggle = { expanded.value = !expanded.value },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MINIMUM_COLOR_STRENGTH = 0.5f
        const val COLOR_TOLERANCE = 0.05f
        const val MINIMUM_TINT_PIXELS = 10
        const val WRITE_SETTLE_TIMEOUT_MILLIS = 5_000L
        const val PENDING_INTERVAL_MILLIS = 1_000L
        const val COMPLETION_FRAMES_MILLIS = 32L
        const val CONFIRMATION_SETTLE_MILLIS = 2_500L
        const val PAYLOAD_SIZE = 64
        const val COPIED_PAYLOAD =
            "A3 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 12 13 14 15 16 17 18 19 1A 1B 1C 1D 1E 1F" +
                " 20 21 22 23 24 25 26 27 28 29 2A 2B 2C 2D 2E 2F 30 31 32 33 34 35 36 37 38 39 3A 3B 3C 3D 3E FF"
        val PAYLOAD = ByteArray(PAYLOAD_SIZE) { it.toByte() }.apply {
            this[0] = 0xA3.toByte()
            this[lastIndex] = 0xFF.toByte()
        }
        val ENTRY = RxLogEntry(
            id = Uuid.normalized("10000000-0000-0000-0000-000000000001"),
            radioID = Uuid.normalized("10000000-0000-0000-0000-000000000002"),
            receivedAtEpochMs = 0,
            snr = null,
            rssi = null,
            routeType = RouteType.DIRECT.raw,
            payloadType = RxLogPayloadType.RAW_CUSTOM,
            pathLength = 0,
            packetHash = "packet-hash",
            packetPayloadSize = PAYLOAD_SIZE,
            rawPayload = PAYLOAD,
        )
    }
}
