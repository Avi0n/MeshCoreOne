package one.meshcore.shared.chat.ui

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.ChatRenderState
import one.meshcore.shared.chat.ChatThreadRenderState
import one.meshcore.shared.chat.ComposerState
import one.meshcore.shared.chat.EnvInputs
import one.meshcore.shared.chat.MessageFragmentBuilder
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.chat.SenderNameResolver
import one.meshcore.shared.chat.buildThreadInputs
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * The mandated W2 scroll benchmark (§8 invariant 11): populate a large history (1000 messages built the
 * same way [one.meshcore.shared.chat.ChatCoordinator] builds them) into the real
 * `LazyColumn(reverseLayout = true)` thread surface and fling it on the mid-range device (Pixel 5),
 * capturing per-frame `FrameMetrics.TOTAL_DURATION` to report jank.
 *
 * The screen is hosted in a [ComposeView] under a plain [ActivityScenario] rather than a Compose test rule,
 * so it renders under the production frame clock; the flings are injected as real [MotionEvent]s so the
 * physics fling animates in real time and FrameMetrics observes genuine rendered frames. Run on device
 * with `connectedDebugAndroidTest` (filtering this class), then read the stats logged under [TAG] with
 * `adb logcat -s ChatScrollBench`.
 */
class ChatThreadScrollBenchmarkInstrumentedTest {
    @Test
    fun flingOverAThousandMessagesReportsFrameStats() {
        val state = largeHistoryState(MESSAGE_COUNT)
        val durationsNs = Collections.synchronizedList(mutableListOf<Long>())
        val handlerThread = HandlerThread("frame-metrics").apply { start() }
        val handler = Handler(handlerThread.looper)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            durationsNs.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }

        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            val view = ComposeView(activity).apply {
                setContent {
                    TimestampFormatting {
                        MaterialTheme {
                            ChatThreadScreen(
                                screenState = ChatThreadScreenState(
                                    thread = state,
                                    composer = ComposerState(maxBytes = DM_BYTE_BUDGET),
                                    title = "Benchmark",
                                    isReady = true,
                                    isEncrypted = true,
                                ),
                                actions = noopActions(),
                            )
                        }
                    }
                }
            }
            activity.setContentView(view)
            activity.window.addOnFrameMetricsAvailableListener(listener, handler)
        }
        // Let the first composition + initial layout of the 1000-item list settle before measuring.
        Thread.sleep(WARMUP_MILLIS)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val lowY = metrics.heightPixels * FLING_LOW_FRACTION
        val highY = metrics.heightPixels * FLING_HIGH_FRACTION
        repeat(FLING_CYCLES) {
            injectFling(centerX, lowY, centerX, highY)
            Thread.sleep(SETTLE_MILLIS)
            injectFling(centerX, highY, centerX, lowY)
            Thread.sleep(SETTLE_MILLIS)
        }

        scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        handlerThread.quitSafely()
        scenario.close()

        reportFrameStats(durationsNs.toList())
        assertTrue("No frames were captured during the fling", durationsNs.isNotEmpty())
    }

    /** Injects a quick swipe as real touch events, producing a high-velocity fling under the real clock. */
    private fun injectFling(startX: Float, startY: Float, endX: Float, endY: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float, time: Long) =
            MotionEvent.obtain(downTime, time, action, x, y, 0).also {
                instrumentation.sendPointerSync(it)
                it.recycle()
            }
        event(MotionEvent.ACTION_DOWN, startX, startY, downTime)
        for (step in 1..FLING_STEPS) {
            val fraction = step.toFloat() / FLING_STEPS
            val x = startX + (endX - startX) * fraction
            val y = startY + (endY - startY) * fraction
            event(MotionEvent.ACTION_MOVE, x, y, downTime + step.toLong() * FLING_STEP_MILLIS)
        }
        event(MotionEvent.ACTION_UP, endX, endY, downTime + FLING_STEPS.toLong() * FLING_STEP_MILLIS)
    }

    private fun reportFrameStats(durationsNs: List<Long>) {
        if (durationsNs.isEmpty()) {
            Log.w(TAG, "No frames captured")
            return
        }
        val millis = durationsNs.map { it / NANOS_PER_MILLI }.sorted()
        val total = millis.size
        val jank60 = millis.count { it > JANK_60HZ_MS }
        val jank90 = millis.count { it > JANK_90HZ_MS }
        Log.i(TAG, "frames=$total")
        Log.i(TAG, "p50=${percentile(millis, PERCENTILE_50)}ms p90=${percentile(millis, PERCENTILE_90)}ms")
        Log.i(TAG, "p95=${percentile(millis, PERCENTILE_95)}ms max=${millis.last()}ms")
        Log.i(TAG, "jank>16.7ms=$jank60 (${percent(jank60, total)}%) jank>11.1ms=$jank90 (${percent(jank90, total)}%)")
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        return sorted[(fraction * (sorted.size - 1)).toInt()]
    }

    private fun percent(count: Int, total: Int): Int = if (total == 0) 0 else (count * PERCENT_SCALE) / total

    private fun largeHistoryState(count: Int): ChatThreadRenderState {
        val resolver = SenderNameResolver { message ->
            NodeNameResolution(displayName = message.senderNodeName ?: "Me", matchKind = NodeNameMatchKind.EXACT)
        }
        val dtos = (0 until count).map { fakeMessage(it) }
        val env = EnvInputs(currentUserName = "Me", showIncomingSendTime = false)
        val items = buildThreadInputs(dtos, dividerMessageID = null, resolveSender = resolver)
            .map { (message, inputs) -> MessageFragmentBuilder.makeItem(message, inputs, env) }
        return ChatThreadRenderState.EMPTY.copy(
            items = items,
            phase = ChatRenderState.LoadPhase.LOADED,
            hasMoreMessages = false,
        )
    }

    private fun fakeMessage(index: Int): MessageDto {
        val incoming = index % 2 == 0
        return MessageDto(
            id = Uuid.normalized("00000000-0000-0000-0000-" + index.toString().padStart(UUID_TAIL_WIDTH, '0')),
            radioID = BENCH_RADIO,
            text = "Benchmark message number $index with enough text to wrap onto a second line sometimes.",
            timestamp = BASE_TIMESTAMP + index.toLong() * SECONDS_PER_MESSAGE,
            createdAt = (BASE_TIMESTAMP + index.toLong() * SECONDS_PER_MESSAGE).toDouble(),
            direction = if (incoming) MessageDirection.INCOMING else MessageDirection.OUTGOING,
            status = if (incoming) MessageStatus.DELIVERED else MessageStatus.SENT,
            textType = TextType.PLAIN,
            pathLength = 0,
            isRead = true,
            heardRepeats = 0,
            sendCount = 1,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            linkPreviewFetched = false,
            containsSelfMention = false,
            mentionSeen = false,
            timestampCorrected = false,
            contactID = BENCH_CONTACT,
            channelIndex = null,
            senderNodeName = if (incoming) "Sender $index" else null,
        )
    }

    private fun noopActions() = ChatThreadActions(
        onTextChange = {},
        onSend = {},
        onNearTop = {},
        onNearBottomChanged = {},
        onScrollToBottom = {},
        onRetry = {},
        onDelete = {},
        onClearError = {},
        onClearSendError = {},
        onBack = {},
    )

    private companion object {
        const val TAG = "ChatScrollBench"
        const val MESSAGE_COUNT = 1000
        const val DM_BYTE_BUDGET = 150
        const val FLING_CYCLES = 12
        const val FLING_STEPS = 10
        const val FLING_STEP_MILLIS = 8L
        const val WARMUP_MILLIS = 1_500L
        const val SETTLE_MILLIS = 700L
        const val FLING_LOW_FRACTION = 0.75f
        const val FLING_HIGH_FRACTION = 0.25f
        const val NANOS_PER_MILLI = 1_000_000.0
        const val JANK_60HZ_MS = 16.7
        const val JANK_90HZ_MS = 11.1
        const val PERCENTILE_50 = 0.50
        const val PERCENTILE_90 = 0.90
        const val PERCENTILE_95 = 0.95
        const val PERCENT_SCALE = 100
        const val UUID_TAIL_WIDTH = 12
        const val BASE_TIMESTAMP = 1_700_000_000L
        const val SECONDS_PER_MESSAGE = 60L
        val BENCH_RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val BENCH_CONTACT: Uuid = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
    }
}
