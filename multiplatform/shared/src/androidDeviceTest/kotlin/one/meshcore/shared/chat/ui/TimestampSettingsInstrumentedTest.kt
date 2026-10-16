package one.meshcore.shared.chat.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.canCommitBackupImport
import one.meshcore.shared.backup.previewPickedBackupJson
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.LocationReportFormat
import one.meshcore.shared.remotenode.TelemetryChartPoint
import one.meshcore.shared.remotenode.ui.MetricChartView
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_timestamp_unavailable
import one.meshcore.shared.settings.ui.BackupImportSheetBody
import one.meshcore.shared.settings.ui.BackupImportSheetPhase
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TimestampSettingsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun refreshUsesCurrentSettingsAndCompositionOwnsOneSubscription() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        var context by mutableStateOf<Context>(target)
        var owner by mutableStateOf<LifecycleOwner>(Owner())
        var shown by mutableStateOf(true)
        var unrelated by mutableStateOf(0)
        var settings = TimestampSettings(Locale.US, false, UTC)
        var subscriptions = 0
        var unsubscriptions = 0
        var refresh: () -> Unit = {}
        val read: (Context) -> TimestampSettings = { settings }
        val subscribe: (Context, LifecycleOwner?, () -> Unit) -> (() -> Unit) = { _, _, callback ->
            subscriptions++
            refresh = callback
            { unsubscriptions++ }
        }
        compose.setContent {
            if (shown) {
                val formatter = rememberAndroidTimestampFormatter(
                    context,
                    owner,
                    context.resources.configuration,
                    read,
                    subscribe,
                    unavailableLabel = "Unavailable",
                )
                MaterialTheme {
                    Column {
                        Text(chatClockLabel(EPOCH.toDouble(), formatter), Modifier.testTag(CLOCK_TAG))
                        Text(unrelated.toString())
                    }
                }
            }
        }
        assertEquals(
            "8:13 PM",
            compose.onNodeWithTag(CLOCK_TAG).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                .replace('\u202F', ' ').replace('\u00A0', ' '),
        )
        compose.runOnIdle { unrelated++ }
        compose.waitForIdle()
        assertEquals(1, subscriptions)
        assertEquals(0, unsubscriptions)
        compose.runOnIdle {
            settings = TimestampSettings(Locale.GERMANY, true, UTC)
            refresh()
        }
        compose.onNodeWithTag(CLOCK_TAG).assertTextEquals("20:13")
        assertEquals(1, subscriptions)
        compose.runOnIdle {
            settings = TimestampSettings(Locale.GERMANY, true, ZoneId.of("America/Los_Angeles"))
            refresh()
        }
        compose.onNodeWithTag(CLOCK_TAG).assertTextEquals("13:13")
        compose.runOnIdle { context = ContextWrapper(target) }
        compose.waitForIdle()
        assertEquals(2, subscriptions)
        assertEquals(1, unsubscriptions)
        compose.runOnIdle { owner = Owner() }
        compose.waitForIdle()
        assertEquals(3, subscriptions)
        assertEquals(2, unsubscriptions)
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertEquals(3, unsubscriptions)
        File(target.cacheDir, "timestamp-settings-lifetime.txt").writeText(
            "injected_signal=true native_delivery=false subscriptions=$subscriptions unsubscriptions=$unsubscriptions",
        )
    }

    @Test
    fun nativeCallbacksUseTheSettingAndTimezoneMetadataAndResumeFilter() {
        var refreshes = 0
        val callbacks = TimestampSettingsCallbacks { refreshes++ }
        assertEquals(Uri.parse("content://settings/system/time_12_24"), callbacks.settingUri)
        assertEquals(1, callbacks.timezoneFilter.countActions())
        assertEquals("android.intent.action.TIMEZONE_CHANGED", callbacks.timezoneFilter.getAction(0))
        callbacks.observer.onChange(false)
        callbacks.observer.onChange(false, callbacks.settingUri)
        callbacks.receiver.onReceive(null, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        callbacks.resumeObserver.onStateChanged(Owner(), Lifecycle.Event.ON_PAUSE)
        assertEquals(3, refreshes)
        callbacks.resumeObserver.onStateChanged(Owner(), Lifecycle.Event.ON_RESUME)
        assertEquals(4, refreshes)
    }

    @Test
    fun actualFactoryReadsTheAppResourceLocaleWithoutARefreshSignal() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        fun localized(locale: Locale): Context = target.createConfigurationContext(
            Configuration(target.resources.configuration).apply { setLocale(locale) },
        )
        var context by mutableStateOf(localized(Locale.US))
        var shown by mutableStateOf(true)
        val dateEpoch = LocalDate.of(2026, 8, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toEpochSecond()
        compose.setContent {
            if (shown) {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides context.resources.configuration,
                ) {
                    val formatter = rememberTimestampFormatter()
                    MaterialTheme {
                        Text(formatter.date(dateEpoch) ?: formatter.unavailable, Modifier.testTag(CLOCK_TAG))
                    }
                }
            }
        }
        compose.onNodeWithTag(CLOCK_TAG).assertTextEquals("Aug 15, 2026")
        compose.runOnIdle { context = localized(Locale.GERMANY) }
        compose.onNodeWithTag(CLOCK_TAG).assertTextEquals("15.08.2026")
        val actual = readTimestampSettings(context)
        assertEquals(Locale.GERMANY, actual.locale)
        assertEquals(android.text.format.DateFormat.is24HourFormat(context), actual.is24Hour)
        assertEquals(ZoneId.systemDefault(), actual.zone)
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
    }

    @Test
    fun realSubscriptionReceivesLifecycleResumeAndUnsubscribes() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = Owner()
        var refreshes = 0
        compose.runOnIdle {
            val unsubscribe = subscribeTimestampSettings(target, owner) { refreshes++ }
            val lifecycle = owner.lifecycle as LifecycleRegistry
            try {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                assertEquals(1, refreshes)
            } finally {
                unsubscribe()
            }
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(1, refreshes)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        File(target.cacheDir, "timestamp-settings-real-subscription.txt").writeText(
            "registered_native_subscription=true lifecycle_resume=true disposed=true " +
                "delivered_setting_change=false delivered_timezone_broadcast=false refreshes=$refreshes",
        )
    }

    @Test
    fun hotFormatterReplacementUpdatesChartLabelsAndRestoresPixels() {
        var formatter by mutableStateOf<TimestampFormatter>(
            AndroidTimestampFormatter(Locale.US, false, UTC, "Unavailable"),
        )
        var clockWidth = 0
        val chartEpoch = Instant.parse("2026-08-16T00:30:00Z").epochSecond
        val points = listOf(point(chartEpoch, 10), point(chartEpoch + 30, 30))
        compose.setContent {
            CompositionLocalProvider(LocalTimestampFormatter provides formatter) {
                MaterialTheme {
                    Column(Modifier.width(320.dp).testTag(CAPTURE_TAG)) {
                        val current = LocalTimestampFormatter.current
                        Text(
                            chatClockLabel(EPOCH.toDouble(), current),
                            Modifier.testTag(CLOCK_TAG).onSizeChanged { clockWidth = it.width },
                        )
                        MetricChartView(title = "Timestamp fixture", points = points, unit = "ms")
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Aug 16").assertCountEquals(4)
        val originalWidth = clockWidth
        val original = capture("us12")
        compose.runOnIdle { formatter = AndroidTimestampFormatter(Locale.GERMANY, true, UTC, "Unavailable") }
        compose.onNodeWithTag(CLOCK_TAG).assertTextEquals("20:13")
        compose.waitForIdle()
        assertTrue("Current clock preference updates measured text width", clockWidth != originalWidth)
        compose.onAllNodesWithText("16. Aug.").assertCountEquals(4)
        capture("de24")
        compose.runOnIdle {
            formatter =
                AndroidTimestampFormatter(Locale.GERMANY, true, ZoneId.of("America/Los_Angeles"), "Unavailable")
        }
        compose.onNodeWithTag(CLOCK_TAG).assertTextEquals("13:13")
        compose.onAllNodesWithText("15. Aug.").assertCountEquals(4)
        capture("los-angeles")
        compose.runOnIdle { formatter = AndroidTimestampFormatter(Locale.US, false, UTC, "Unavailable") }
        compose.onAllNodesWithText("Aug 16").assertCountEquals(4)
        val restored = capture("restored")
        assertEquals(originalWidth, clockWidth)
        assertTrue("Restored locale/hour/zone has identical pixels", original.sameAs(restored))
    }

    @Test
    fun acceptedExtremePreviewShowsOneGeneratedFallbackAndRecovers() {
        val unavailable = runBlocking { getString(Res.string.android_timestamp_unavailable) }
        val formatter = AndroidTimestampFormatter(Locale.US, true, UTC, unavailable)
        val extreme = 1e15
        var preview by mutableStateOf(previewPickedBackupJson(backupJson(extreme)))
        val original = preview
        compose.setContent {
            CompositionLocalProvider(LocalTimestampFormatter provides formatter) {
                MaterialTheme {
                    Column(Modifier.width(320.dp).testTag(CAPTURE_TAG)) {
                        BackupImportSheetBody(
                            phase = BackupImportSheetPhase.PREVIEW,
                            preview = preview,
                            importResult = null,
                            failure = null,
                            isCancellingImport = false,
                        )
                    }
                }
            }
        }
        compose.onAllNodesWithText(unavailable, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(unavailable, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(extreme, preview.exportDateEpochSeconds, 0.0)
        assertTrue(canCommitBackupImport(preview.manifestValid, isRadioConnected = false))
        capture("backup-unavailable")
        compose.runOnIdle { preview = previewPickedBackupJson(backupJson(EPOCH.toDouble())) }
        compose.onNodeWithText("Aug 15, 2026, 20:13", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText(unavailable, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(EPOCH.toDouble(), preview.exportDateEpochSeconds, 0.0)
        assertTrue(canCommitBackupImport(preview.manifestValid, isRadioConnected = false))
        capture("backup-recovered")
        assertEquals(extreme, original.exportDateEpochSeconds, 0.0)
        record(
            "backup-preview-state",
            "phase=PREVIEW originalEpoch=${original.exportDateEpochSeconds} " +
                "recoveredEpoch=${preview.exportDateEpochSeconds} " +
                "manifestBefore=${original.manifestValid} manifestAfter=${preview.manifestValid} " +
                "fixtureDisconnectedEligibility=true importInvoked=false",
        )
    }

    @Test
    fun fallbackOnlyReplacementUpdatesTheFactoryWithoutResubscribing() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = Owner()
        var label by mutableStateOf("First unavailable")
        var shown by mutableStateOf(true)
        var subscriptions = 0
        var unsubscriptions = 0
        var reads = 0
        var current: TimestampFormatter? = null
        val read: (Context) -> TimestampSettings = {
            reads++
            TimestampSettings(Locale.US, true, UTC)
        }
        val subscribe: (Context, LifecycleOwner?, () -> Unit) -> (() -> Unit) = { _, _, _ ->
            subscriptions++
            { unsubscriptions++ }
        }
        compose.setContent {
            if (shown) {
                val formatter = rememberAndroidTimestampFormatter(
                    target,
                    owner,
                    target.resources.configuration,
                    read,
                    subscribe,
                    unavailableLabel = label,
                )
                current = formatter
                MaterialTheme {
                    Column(Modifier.width(320.dp).testTag(CAPTURE_TAG)) {
                        Text(chatMonthDayLabel(Long.MAX_VALUE, formatter), Modifier.testTag(CLOCK_TAG))
                    }
                }
            }
        }
        compose.onNodeWithTag(CLOCK_TAG).assertTextEquals("First unavailable")
        val initial = current
        val original = capture("fallback-first")
        compose.runOnIdle { label = "Second unavailable" }
        compose.onNodeWithTag(CLOCK_TAG).assertTextEquals("Second unavailable")
        assertNotSame(initial, current)
        assertEquals(1, reads)
        assertEquals(1, subscriptions)
        assertEquals(0, unsubscriptions)
        capture("fallback-second")
        compose.runOnIdle { label = "First unavailable" }
        compose.onNodeWithTag(CLOCK_TAG).assertTextEquals("First unavailable")
        val restored = capture("fallback-restored")
        assertTrue("Restored fallback has identical pixels", original.sameAs(restored))
        assertEquals(1, reads)
        assertEquals(1, subscriptions)
        assertEquals(0, unsubscriptions)
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertEquals(1, unsubscriptions)
        record(
            "fallback-lifetime",
            "settingsReads=$reads subscriptions=$subscriptions unsubscriptions=$unsubscriptions",
        )
    }

    @Test
    fun heldScrubUsesTheHotFormatterAndMatchesAFreshGesture() {
        val unavailable = runBlocking { getString(Res.string.android_timestamp_unavailable) }
        val american = AndroidTimestampFormatter(Locale.US, false, UTC, unavailable)
        val german = AndroidTimestampFormatter(Locale.GERMANY, true, UTC, unavailable)
        var formatter by mutableStateOf<TimestampFormatter>(american)
        val points = listOf(point(EPOCH, 10), point(EPOCH + 30, 30))
        val actions = mutableListOf<String>()
        var held: HeldTouch? = null
        compose.setContent {
            CompositionLocalProvider(LocalTimestampFormatter provides formatter) {
                MaterialTheme {
                    MetricChartView(
                        title = SCRUB_TITLE,
                        points = points,
                        unit = "ms",
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }
        val plot = compose.onNodeWithContentDescription(SCRUB_TITLE)
        val beforeText = LocationReportFormat.absoluteTime((EPOCH + 30).toDouble(), american)
        val afterText = LocationReportFormat.absoluteTime((EPOCH + 30).toDouble(), german)
        assertFalse("The expected drawn timestamp changes", beforeText == afterText)
        try {
            val idleAmerican = capture("scrub-idle-us", plot)
            held = touchAtPlot(plot)
            sendTouch(held, MotionEvent.ACTION_DOWN, actions)
            awaitHeldPlot(plot, idleAmerican)
            val heldAmerican = capture("scrub-held-us", plot)
            sendTouch(held, MotionEvent.ACTION_UP, actions)
            held = null
            assertTrue("US release restores idle pixels", idleAmerican.sameAs(capture("scrub-released-us", plot)))

            compose.runOnIdle {
                formatter = german
                actions += "formatter=German24 appliedUptime=${SystemClock.uptimeMillis()} downStillActive=false"
            }
            val idleGerman = capture("scrub-idle-de", plot)
            compose.runOnIdle {
                formatter = american
                actions += "formatter=US12 appliedUptime=${SystemClock.uptimeMillis()} downStillActive=false"
            }
            assertTrue("US settings restore idle pixels", idleAmerican.sameAs(capture("scrub-idle-us-restored", plot)))
            held = touchAtPlot(plot)
            sendTouch(held, MotionEvent.ACTION_DOWN, actions)
            awaitHeldPlot(plot, idleAmerican)
            assertTrue("A second US hold has the same chip", heldAmerican.sameAs(capture("scrub-held-us-again", plot)))
            val heldSize = plot.fetchSemanticsNode().size
            compose.runOnIdle {
                formatter = german
                actions += "formatter=German24 appliedUptime=${SystemClock.uptimeMillis()} downStillActive=true"
            }
            val hotGerman = capture("scrub-held-hot-de", plot)
            assertEquals("The plot geometry stays unchanged", heldSize, plot.fetchSemanticsNode().size)
            assertFalse("The held chip uses the new timestamp", heldAmerican.sameAs(hotGerman))
            assertFalse("The same DOWN remains visibly held", idleGerman.sameAs(hotGerman))
            sendTouch(held, MotionEvent.ACTION_UP, actions)
            held = null
            assertTrue(
                "Hot German release restores idle pixels",
                idleGerman.sameAs(capture("scrub-released-hot-de", plot)),
            )

            held = touchAtPlot(plot)
            sendTouch(held, MotionEvent.ACTION_DOWN, actions)
            awaitHeldPlot(plot, idleGerman)
            val freshGerman = capture("scrub-held-fresh-de", plot)
            assertTrue("Hot held pixels equal a fresh German held reference", hotGerman.sameAs(freshGerman))
            sendTouch(held, MotionEvent.ACTION_UP, actions)
            held = null
            assertTrue(
                "Fresh German release restores idle pixels",
                idleGerman.sameAs(capture("scrub-released-fresh-de", plot)),
            )
        } finally {
            try {
                held?.let { sendTouch(it, MotionEvent.ACTION_UP, actions) }
            } finally {
                record(
                    "scrub-native-actions",
                    "source=SOURCE_TOUCHSCREEN expectedOld=$beforeText expectedHot=$afterText " +
                        "proof=drawn_chip_freshness osSignalDelivery=false\n" + actions.joinToString("\n"),
                )
            }
        }
    }

    private fun touchAtPlot(plot: SemanticsNodeInteraction): HeldTouch {
        compose.waitForIdle()
        val node = plot.fetchSemanticsNode()
        assertTrue("Plot has useful geometry", node.size.width > 0 && node.size.height > 0)
        val position = node.positionOnScreen + Offset(node.size.width * SCRUB_X_FRACTION, node.size.height / 2f)
        return HeldTouch(SystemClock.uptimeMillis(), position)
    }

    private fun sendTouch(touch: HeldTouch, action: Int, actions: MutableList<String>) {
        val eventTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(touch.downTime, eventTime, action, touch.position.x, touch.position.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        val started = SystemClock.uptimeMillis()
        try {
            InstrumentationRegistry.getInstrumentation().sendPointerSync(event)
        } finally {
            actions += "action=$action downTime=${touch.downTime} eventTime=$eventTime source=${event.source} " +
                "x=${touch.position.x} y=${touch.position.y} " +
                "sendStarted=$started sendEnded=${SystemClock.uptimeMillis()}"
            event.recycle()
        }
    }

    private fun awaitHeldPlot(plot: SemanticsNodeInteraction, idle: Bitmap) {
        compose.waitUntil(timeoutMillis = SCRUB_TIMEOUT_MS) {
            !idle.sameAs(plot.captureToImage().asAndroidBitmap())
        }
    }

    private fun backupJson(epoch: Double): String = """
        {"version":1,"exportDate":$epoch,"appVersion":"1","appBuild":"1",
        "manifest":{"deviceCount":0,"contactCount":0,"channelCount":0,"messageCount":0,
        "messageRepeatCount":0,"reactionCount":0,"roomMessageCount":0,"remoteNodeSessionCount":0,
        "savedTracePathCount":0,"blockedChannelSenderCount":0,"nodeStatusSnapshotCount":0},
        "devices":[],"contacts":[],"channels":[],"messages":[],"messageRepeats":[],"reactions":[],
        "roomMessages":[],"remoteNodeSessions":[],"savedTracePaths":[],"blockedChannelSenders":[],
        "nodeStatusSnapshots":[]}
    """.trimIndent()

    private fun record(name: String, value: String) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        File(target.cacheDir, "timestamp-settings-$name.txt").writeText(value)
    }

    private fun capture(name: String, node: SemanticsNodeInteraction = compose.onNodeWithTag(CAPTURE_TAG)): Bitmap {
        val started = SystemClock.uptimeMillis()
        compose.waitForIdle()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        File(target.cacheDir, "timestamp-settings-$name.txt").writeText(node.printToString())
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(target.cacheDir, "timestamp-settings-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        record(
            "$name-receipt",
            "source=ComposeNode.captureToImage captureStarted=$started " +
                "captureCompleted=${SystemClock.uptimeMillis()} " +
                "width=${bitmap.width} height=${bitmap.height} nativeWindowScreenshot=false",
        )
        return bitmap
    }

    private class HeldTouch(val downTime: Long, val position: Offset)

    private class Owner : LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry(this)
    }

    private companion object {
        const val CLOCK_TAG = "timestamp-clock"
        const val CAPTURE_TAG = "timestamp-chart"
        const val SCRUB_TITLE = "Timestamp held fixture"
        const val SCRUB_X_FRACTION = 0.75f
        const val SCRUB_TIMEOUT_MS = 5_000L
        val UTC: ZoneId = ZoneId.of("UTC")
        val EPOCH: Long = Instant.parse("2026-08-15T20:13:00Z").epochSecond
        val ID = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
        fun point(epoch: Long, rtt: Int) = TelemetryChartPoint(ID, epoch.toDouble(), rtt.toDouble())
    }
}
