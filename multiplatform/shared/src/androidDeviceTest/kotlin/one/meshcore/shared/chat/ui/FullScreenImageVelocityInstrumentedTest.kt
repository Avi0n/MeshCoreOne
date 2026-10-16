package one.meshcore.shared.chat.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class FullScreenImageVelocityInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private var dismissals = 0
    private var stepPx = 0f
    private var separationPx = 0f
    private val dismissalCallback = mutableStateOf<() -> Unit>({
        dismissals++
        visible.value = false
    })

    @Test
    fun shortDownwardFlickDismissesOnRelease() = gesture("downward", SHORT_STEP_DP, FAST_STEP_MS, 1)

    @Test
    fun shortUpwardFlickDismissesOnRelease() = gesture("upward", -SHORT_STEP_DP, FAST_STEP_MS, 1)

    @Test
    fun shortSlowDragReturnsWithoutDismissal() = gesture("slow", SHORT_STEP_DP, SLOW_STEP_MS, 0)

    @Test
    fun longSlowDragStillDismissesByDistance() = gesture("distance", LONG_STEP_DP, SLOW_STEP_MS, 1)

    @Test
    fun cancelledShortFlickDoesNotDismiss() = gesture("cancelled", SHORT_STEP_DP, FAST_STEP_MS, 0, cancelled = true)

    @Test
    fun pausedShortFlickDoesNotDismiss() = gesture("paused", SHORT_STEP_DP, FAST_STEP_MS, 0, pauseMillis = SLOW_STEP_MS)

    @Test
    fun shrinkingPinchAtMinimumScaleDoesNotBecomeAFlick() {
        show(SHORT_STEP_DP)
        capture("pinch", "initial")
        compose.onNode(isDialog()).performTouchInput {
            val first = center - Offset(separationPx, separationPx)
            val second = center + Offset(separationPx, separationPx)
            down(0, first)
            down(1, second)
            repeat(MOVE_COUNT) { index ->
                val delta = Offset(0f, stepPx * (index + 1))
                updatePointerTo(0, first + delta)
                updatePointerTo(1, second - delta)
                move(delayMillis = FAST_STEP_MS)
            }
            up(0)
            up(1)
        }
        capture("pinch", "released")
        receipt("pinch", "two pointers shrink symmetrically; 16ms explicit MOVE intervals", 0)
    }

    @Test
    fun zoomedFlickStaysOpenAndZoomResetRestoresDismissal() {
        show(SHORT_STEP_DP)
        val initialWidth = capture("zoom", "initial")
        assertTrue("Initial bitmap is visible", initialWidth > 0)
        val initialClock = compose.mainClock.currentTime
        compose.onNode(isDialog()).performTouchInput {
            advanceEventTime(eventPeriodMillis)
            doubleClick(center)
        }
        val zoomWidth = capture("zoom", "zoomed")
        assertEquals("Double tap triples actual bitmap width", initialWidth * EXPECTED_ZOOM_SCALE, zoomWidth)
        compose.onNode(isDialog()).performTouchInput { flick() }
        capture("zoom", "flick")
        compose.runOnIdle { assertEquals("Zoomed flick cannot dismiss", 0, dismissals) }
        compose.onNode(isDialog()).performTouchInput { doubleClick(center) }
        val restoredWidth = capture("zoom", "reset")
        assertEquals("Double tap restores bitmap width", initialWidth, restoredWidth)
        compose.onNode(isDialog()).performTouchInput { flick() }
        capture("zoom", "released")
        receipt(
            "zoom",
            "initial_test_clock_ms=$initialClock leading_delay_ms=16 " +
                "double-tap zoom, 48ms flick, zoom reset, fresh 48ms flick",
            1,
        )
    }

    @Test
    fun disposalDuringFlickDoesNotDismissAndRemountStartsFresh() {
        show(SHORT_STEP_DP)
        val initialWidth = capture("disposal", "initial")
        assertTrue("Initial bitmap is visible", initialWidth > 0)
        compose.onNode(isDialog()).performTouchInput {
            down(center)
            repeat(MOVE_COUNT) { moveBy(Offset(0f, stepPx), delayMillis = FAST_STEP_MS) }
        }
        compose.runOnIdle { visible.value = false }
        capture("disposal", "hidden")
        compose.runOnIdle { assertEquals("Disposal is not release", 0, dismissals) }
        compose.runOnIdle { visible.value = true }
        assertEquals("Remount restores bitmap width", initialWidth, capture("disposal", "remounted"))
        compose.onNode(isDialog()).performTouchInput { flick() }
        capture("disposal", "released")
        receipt("disposal", "dispose during held 48ms motion, remount, fresh 48ms flick", 1)
    }

    @Test
    fun movementBelowTouchSlopLeavesTheHeldBitmapStill() {
        show(SUB_SLOP_STEP_DP)
        val initial = captureBounds("slop", "initial")
        assertTrue("Initial bitmap is visible", initial.width > 0)
        compose.onNode(isDialog()).performTouchInput {
            down(center)
            moveBy(Offset(0f, stepPx), delayMillis = SLOW_STEP_MS)
        }
        val held = captureBounds("slop", "held")
        compose.onNode(isDialog()).performTouchInput { up() }
        capture("slop", "released")
        receipt("slop", "2dp below-slop motion held, then released", 0)
        assertEquals("Below-slop input does not move the bitmap", initial, held)
    }

    @Test
    fun returningDragFollowedByTapDoesNotBecomeDoubleTap() {
        show(SHORT_STEP_DP)
        val initialWidth = capture("returning", "initial")
        assertTrue("Initial bitmap is visible", initialWidth > 0)
        compose.onNode(isDialog()).performTouchInput {
            advanceEventTime(eventPeriodMillis)
            down(center)
            moveBy(Offset(0f, stepPx), delayMillis = RETURN_STEP_MS)
            moveBy(Offset(0f, -stepPx), delayMillis = RETURN_STEP_MS)
            up()
            advanceEventTime(RETURN_STEP_MS)
            down(center)
            up()
        }
        val afterWidth = capture("returning", "tapped")
        receipt("returning", "28dp out and back at64ms per MOVE, then tap after64ms", 0)
        compose.onNode(isDialog()).performTouchInput {
            advanceEventTime(DOUBLE_TAP_RESET_MS)
            doubleClick(center)
        }
        val freshWidth = capture("returning", "zoomed")
        assertEquals("A returning drag is not a first tap", initialWidth, afterWidth)
        assertEquals("A fresh double tap still zooms", initialWidth * EXPECTED_ZOOM_SCALE, freshWidth)
    }

    @Test
    fun stationaryTwoFingerTouchFollowedByTapDoesNotZoom() {
        show(SHORT_STEP_DP)
        val initialWidth = capture("multitap", "initial")
        assertTrue("Initial bitmap is visible", initialWidth > 0)
        compose.onNode(isDialog()).performTouchInput {
            advanceEventTime(eventPeriodMillis)
            down(0, center - Offset(separationPx, 0f))
            down(1, center + Offset(separationPx, 0f))
            advanceEventTime(RETURN_STEP_MS)
            up(0)
            up(1)
            advanceEventTime(RETURN_STEP_MS)
            down(center)
            up()
        }
        val afterWidth = capture("multitap", "tapped")
        receipt("multitap", "stationary two-finger touch, then single tap after64ms", 0)
        assertEquals("Two fingers cannot seed double-tap zoom", initialWidth, afterWidth)
        assertEquals("A fresh double tap recovers", initialWidth * EXPECTED_ZOOM_SCALE, freshDoubleTapWidth("multitap"))
    }

    @Test
    fun singleTapFollowedByStationaryTwoFingerTouchDoesNotZoom() {
        show(SHORT_STEP_DP)
        val initialWidth = capture("tapmulti", "initial")
        assertTrue("Initial bitmap is visible", initialWidth > 0)
        compose.onNode(isDialog()).performTouchInput {
            advanceEventTime(eventPeriodMillis)
            down(center)
            up()
            advanceEventTime(RETURN_STEP_MS)
            down(0, center - Offset(separationPx, 0f))
            down(1, center + Offset(separationPx, 0f))
            advanceEventTime(RETURN_STEP_MS)
            up(0)
            up(1)
        }
        val afterWidth = capture("tapmulti", "tapped")
        receipt("tapmulti", "single tap, then stationary two-finger touch after64ms", 0)
        assertEquals("Second touch with two fingers cannot double-tap zoom", initialWidth, afterWidth)
        assertEquals("A fresh double tap recovers", initialWidth * EXPECTED_ZOOM_SCALE, freshDoubleTapWidth("tapmulti"))
    }

    @Test
    fun flickWithChangedReleasePositionStillDismisses() {
        show(SHORT_STEP_DP)
        capture("release-position", "initial")
        compose.onNode(isDialog()).performTouchInput {
            down(center)
            repeat(MOVE_COUNT) { moveBy(Offset(0f, stepPx), delayMillis = FAST_STEP_MS) }
            updatePointerTo(0, center + Offset(stepPx, stepPx * MOVE_COUNT))
            up()
        }
        capture("release-position", "released")
        receipt("release-position", "three16ms vertical MOVEs; UP position shifted28dp horizontally", 1)
    }

    @Test
    fun cancelledMotionAllowsFreshDoubleTap() {
        show(SHORT_STEP_DP)
        val initialWidth = capture("cancel-recovery", "initial")
        assertTrue("Initial bitmap is visible", initialWidth > 0)
        compose.onNode(isDialog()).performTouchInput {
            down(center)
            repeat(MOVE_COUNT) { moveBy(Offset(0f, stepPx), delayMillis = FAST_STEP_MS) }
            cancel(delayMillis = 0L)
        }
        capture("cancel-recovery", "cancelled")
        compose.onNode(isDialog()).performTouchInput {
            advanceEventTime(DOUBLE_TAP_RESET_MS)
            doubleClick(center)
        }
        val zoomWidth = capture("cancel-recovery", "zoomed")
        receipt("cancel-recovery", "cancel48ms motion, then fresh double tap after400ms", 0)
        assertEquals("Cancellation leaves fresh double-tap recognition", initialWidth * EXPECTED_ZOOM_SCALE, zoomWidth)
    }

    @Test
    fun offCenterDoubleTapCentersZoomOnItsPosition() {
        show(SHORT_STEP_DP)
        val initial = captureBounds("offcenter", "initial")
        assertTrue("Initial bitmap is visible", initial.width > 0)
        compose.onNode(isDialog()).performTouchInput {
            advanceEventTime(eventPeriodMillis)
            doubleClick(center + Offset(stepPx, stepPx))
        }
        val zoomed = captureBounds("offcenter", "zoomed")
        receipt("offcenter", "double tap28dp right and below viewport center", 0)
        assertEquals("Off-center tap triples bitmap width", initial.width * EXPECTED_ZOOM_SCALE, zoomed.width)
        val expectedShift = -stepPx * EXPECTED_ZOOM_SCALE
        assertTrue(
            "Zoom centers on tap horizontally",
            abs(zoomed.centerX - initial.centerX - expectedShift) <= PIXEL_TOLERANCE,
        )
        assertTrue(
            "Zoom centers on tap vertically",
            abs(zoomed.centerY - initial.centerY - expectedShift) <= PIXEL_TOLERANCE,
        )
    }

    @Test
    fun pinchCanContinueAsOneFingerPan() = pinchContinuation(liftOriginal = false)

    @Test
    fun pinchCanContinueWithTheOtherFingerAfterOriginalLifts() = pinchContinuation(liftOriginal = true)

    private fun freshDoubleTapWidth(name: String): Int {
        compose.onNode(isDialog()).performTouchInput {
            advanceEventTime(DOUBLE_TAP_RESET_MS)
            doubleClick(center)
        }
        return capture(name, "zoomed")
    }

    private fun pinchContinuation(liftOriginal: Boolean) {
        show(SHORT_STEP_DP)
        val name = if (liftOriginal) "transition-original" else "transition"
        val initialWidth = capture(name, "initial")
        assertTrue("Initial bitmap is visible", initialWidth > 0)
        compose.onNode(isDialog()).performTouchInput {
            val separation = separationPx / PINCH_SCALE
            val first = center - Offset(separation, 0f)
            val second = center + Offset(separation, 0f)
            down(0, first)
            down(1, second)
            updatePointerTo(0, first - Offset(separation, 0f))
            updatePointerTo(1, second + Offset(separation, 0f))
            move(delayMillis = RETURN_STEP_MS)
        }
        val zoomed = captureBounds(name, "zoomed")
        compose.onNode(isDialog()).performTouchInput {
            val liftedPointer = if (liftOriginal) 0 else 1
            val remainingPointer = if (liftOriginal) 1 else 0
            up(liftedPointer)
            repeat(MOVE_COUNT) {
                moveBy(pointerId = remainingPointer, delta = Offset(0f, stepPx), delayMillis = FAST_STEP_MS)
            }
            up(remainingPointer)
        }
        val panned = captureBounds(name, "panned")
        receipt(name, "2x pinch, original_lifts=$liftOriginal, remaining finger's84dp pan", 0)
        assertEquals("Pinch doubles bitmap width", initialWidth * PINCH_SCALE, zoomed.width)
        assertEquals("Pan retains pinch zoom", zoomed.width, panned.width)
        assertTrue(
            "Remaining finger still pans",
            abs(panned.centerY - zoomed.centerY - stepPx * MOVE_COUNT) <= PIXEL_TOLERANCE,
        )
    }

    @Test
    fun mountedCallbackReplacementReceivesNormalRelease() = replacedCallback(cancelled = false)

    @Test
    fun mountedCallbackReplacementDoesNotReceiveCancellation() = replacedCallback(cancelled = true)

    private fun replacedCallback(cancelled: Boolean) {
        var oldCalls = 0
        var newCalls = 0
        dismissalCallback.value = { oldCalls++ }
        val name = if (cancelled) "callback-cancel" else "callback-release"
        show(LONG_STEP_DP)
        capture(name, "initial")
        compose.onNode(isDialog()).performTouchInput {
            down(center)
            repeat(MOVE_COUNT) { moveBy(Offset(0f, stepPx), delayMillis = SLOW_STEP_MS) }
        }
        compose.runOnIdle {
            dismissalCallback.value = {
                newCalls++
                visible.value = false
            }
        }
        compose.onNode(isDialog()).performTouchInput { if (cancelled) cancel(delayMillis = 0L) else up() }
        capture(name, "released")
        val expectedNewCalls = if (cancelled) 0 else 1
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(cache, "image-velocity-$name.txt").writeText(
            "180dp held distance drag; old_calls=$oldCalls new_calls=$newCalls cancelled=$cancelled",
        )
        compose.runOnIdle {
            assertEquals("Replaced callback is not invoked", 0, oldCalls)
            assertEquals("Latest callback receives only normal release", expectedNewCalls, newCalls)
        }
    }

    private fun TouchInjectionScope.flick() {
        down(center)
        repeat(MOVE_COUNT) { moveBy(Offset(0f, stepPx), delayMillis = FAST_STEP_MS) }
        up()
    }

    private fun gesture(
        name: String,
        stepDp: Int,
        stepMillis: Long,
        expectedDismissals: Int,
        pauseMillis: Long = 0L,
        cancelled: Boolean = false,
    ) {
        show(stepDp)
        capture(name, "initial")
        val injectedTiming = mutableListOf<String>()
        compose.onNode(isDialog()).performTouchInput {
            down(center)
            injectedTiming += "DOWN relative_ms=0 default_period_ms=$eventPeriodMillis"
            repeat(MOVE_COUNT) { index ->
                moveBy(Offset(0f, stepPx), delayMillis = stepMillis)
                injectedTiming += "MOVE relative_ms=${(index + 1) * stepMillis} delta_y_px=$stepPx"
            }
            advanceEventTime(pauseMillis)
            if (cancelled) cancel(delayMillis = 0L) else up()
            val action = if (cancelled) "CANCEL" else "UP"
            val releaseMillis = MOVE_COUNT * stepMillis + pauseMillis
            injectedTiming += "$action relative_ms=$releaseMillis"
        }
        capture(name, "released")
        receipt(
            name,
            "step_dp=$stepDp moves=$MOVE_COUNT step_ms=$stepMillis\n" +
                "injected_timing_not_measured_native_uptime\n" + injectedTiming.joinToString("\n"),
            expectedDismissals,
        )
    }

    private fun show(stepDp: Int) {
        val bitmap = Bitmap.createBitmap(BITMAP_SIZE, BITMAP_SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.CYAN)
        }.asImageBitmap()
        compose.setContent {
            stepPx = with(LocalDensity.current) { stepDp.dp.toPx() }
            separationPx = with(LocalDensity.current) { PINCH_SEPARATION_DP.dp.toPx() }
            MaterialTheme {
                if (visible.value) {
                    FullScreenBitmapViewer(bitmap, onDismiss = dismissalCallback.value)
                }
            }
        }
    }

    private fun receipt(name: String, action: String, expectedDismissals: Int) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(cache, "image-velocity-$name.txt").writeText(
            "$action\nexpected_dismissals=$expectedDismissals actual_dismissals=$dismissals\n" +
                compose.onAllNodes(isDialog()).printToString(maxDepth = Int.MAX_VALUE),
        )
        compose.runOnIdle { assertEquals("Actual viewer release behavior", expectedDismissals, dismissals) }
    }

    private fun capture(name: String, phase: String): Int = captureBounds(name, phase).width

    private fun captureBounds(name: String, phase: String): BitmapBounds {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(NATIVE_IDLE_MILLIS, NATIVE_IDLE_TIMEOUT_MILLIS)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, "image-velocity-$name-$phase.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it))
        }
        val pixels = IntArray(screenshot.width * screenshot.height)
        screenshot.getPixels(pixels, 0, screenshot.width, 0, 0, screenshot.width, screenshot.height)
        var firstX = screenshot.width
        var lastX = -1
        var firstY = screenshot.height
        var lastY = -1
        pixels.forEachIndexed { index, color ->
            val red = android.graphics.Color.red(color)
            if (android.graphics.Color.green(color) - red > MIN_BITMAP_CHROMA &&
                android.graphics.Color.blue(color) - red > MIN_BITMAP_CHROMA
            ) {
                firstX = minOf(firstX, index % screenshot.width)
                lastX = maxOf(lastX, index % screenshot.width)
                firstY = minOf(firstY, index / screenshot.width)
                lastY = maxOf(lastY, index / screenshot.width)
            }
        }
        screenshot.recycle()
        return BitmapBounds(firstX, firstY, lastX, lastY)
    }

    private data class BitmapBounds(val firstX: Int, val firstY: Int, val lastX: Int, val lastY: Int) {
        val width: Int get() = (lastX - firstX + 1).coerceAtLeast(0)
        val centerX: Float get() = (firstX + lastX + 1) / 2f
        val centerY: Float get() = (firstY + lastY + 1) / 2f
    }

    private companion object {
        const val BITMAP_SIZE = 32
        const val MOVE_COUNT = 3
        const val SHORT_STEP_DP = 28
        const val LONG_STEP_DP = 60
        const val PINCH_SEPARATION_DP = 100
        const val PINCH_SCALE = 2
        const val SUB_SLOP_STEP_DP = 2
        const val PIXEL_TOLERANCE = 1f
        const val RETURN_STEP_MS = 64L
        const val DOUBLE_TAP_RESET_MS = 400L
        const val EXPECTED_ZOOM_SCALE = 3
        const val MIN_BITMAP_CHROMA = 16
        const val NATIVE_IDLE_MILLIS = 1_000L
        const val NATIVE_IDLE_TIMEOUT_MILLIS = 5_000L
        const val FAST_STEP_MS = 16L
        const val SLOW_STEP_MS = 120L
        const val PNG_QUALITY = 100
    }
}
