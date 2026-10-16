package one.meshcore.shared.remotenode.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_collapsed
import one.meshcore.shared.resources.chats_message_action_expanded
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_contact_info
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_contact_info_placeholder
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_device_actions
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_new_password
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_security
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_send_advert
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_tab_telemetry
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_accessibility_reload_telemetry
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_no_sensor_data
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_refresh
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_voltage
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_telemetry
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import java.io.File

internal abstract class RemoteSectionLazyTestSupport {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val testName = TestName()
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    protected lateinit var fixture: RemoteSectionLazyFixture
    protected val labels = RemoteSectionLazyLabels()

    @Before
    fun setUpFixture() {
        fixture = RemoteSectionLazyFixture(compose.activity, scope)
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }

    @After
    fun tearDownFixture() {
        fixture.radio.cancelGates()
        scope.cancel()
        try {
            compose.waitUntil(TIMEOUT_MS) { checkNotNull(scope.coroutineContext[Job]).isCompleted }
            assertTrue(fixture.radio.readJobs.all { it.isCompleted })
            val directory = evidenceDirectory()
            File(directory, "${testName.methodName}-teardown.txt").writeText(
                "ownerCompleted=${checkNotNull(scope.coroutineContext[Job]).isCompleted} " +
                    "readJobs=${fixture.radio.readJobs.size} " +
                    "completedReadJobs=${fixture.radio.readJobs.count { it.isCompleted }} " +
                    "calls=${fixture.radio.calls} cancelledReads=${fixture.radio.cancelledReads}",
            )
        } finally {
            fixture.close()
        }
    }

    protected fun render(content: @Composable () -> Unit) {
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    labels.read()
                    content()
                }
            }
        }
        compose.waitForIdle()
    }

    protected fun assertQuiet() {
        compose.runOnIdle {
            assertEquals(emptyList<String>(), fixture.radio.calls)
            assertEquals(0, fixture.contactReads)
            assertEquals(0, fixture.snapshotReads)
            assertEquals(0, fixture.persistenceWrites)
        }
        assertNoRefresh()
    }

    protected fun assertNoRefresh() {
        compose.onAllNodesWithContentDescription(labels.refresh).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(labels.reloadTelemetry).assertCountEquals(0)
    }

    protected fun assertCollapsed(title: String) {
        header(title).performScrollTo().assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, labels.collapsed),
        )
    }

    protected fun clickHeader(title: String) {
        header(title).performScrollTo().performClick()
        compose.waitForIdle()
    }

    protected fun assertPendingOnly(operation: String) {
        compose.waitUntil(TIMEOUT_MS) { fixture.radio.calls.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf(operation), fixture.radio.calls)
            assertEquals(1, fixture.radio.readJobs.size)
            assertTrue(fixture.radio.readJobs.single().isActive)
            assertEquals(0, fixture.radio.cancelledReads)
        }
    }

    protected fun retainPendingAcrossCollapse(title: String, operation: String) {
        val original = fixture.radio.readJobs.single()
        clickHeader(title)
        assertCollapsed(title)
        assertNoRefresh()
        compose.runOnIdle {
            assertTrue(original.isActive)
            assertFalse(original.isCancelled)
        }
        clickHeader(title)
        header(title).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, labels.expanded),
        )
        assertPendingOnly(operation)
    }

    protected fun capture(name: String, dialog: Boolean = false) {
        val directory = evidenceDirectory()
        val root = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        File(directory, "$name.txt").writeText(root.printToString())
        val bitmap = root.captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it))
        }
        File(directory, "$name-counts.txt").writeText(
            "calls=${fixture.radio.calls} contactReads=${fixture.contactReads} " +
                "snapshotReads=${fixture.snapshotReads} persistenceWrites=${fixture.persistenceWrites} " +
                "cancelledReads=${fixture.radio.cancelledReads} " +
                "readJobs=${fixture.radio.readJobs.size} " +
                "activeReadJobs=${fixture.radio.readJobs.count { it.isActive }}",
        )
    }

    private fun evidenceDirectory(): File =
        File(checkNotNull(compose.activity.getExternalFilesDir(null)), "remote-section-lazy").apply {
            check(mkdirs() || isDirectory)
        }

    private fun header(title: String) = compose.onNode(
        hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription),
    )

    protected companion object {
        const val TIMEOUT_MS = 10_000L
        private const val PNG_QUALITY = 100
    }
}

internal class RemoteSectionLazyLabels {
    lateinit var telemetry: String
    lateinit var noSensors: String
    lateinit var refresh: String
    lateinit var reloadTelemetry: String
    lateinit var collapsed: String
    lateinit var expanded: String
    lateinit var contactInfo: String
    lateinit var contactEditor: String
    lateinit var security: String
    lateinit var actions: String
    lateinit var password: String
    lateinit var sendAdvert: String
    lateinit var telemetryTab: String
    lateinit var history: String
    lateinit var voltage: String

    @Composable
    fun read() {
        telemetry = stringResource(Res.string.remotenodes_remote_nodes_status_telemetry)
        noSensors = stringResource(Res.string.remotenodes_remote_nodes_status_no_sensor_data)
        refresh = stringResource(Res.string.remotenodes_remote_nodes_status_refresh)
        reloadTelemetry = stringResource(Res.string.remotenodes_remote_nodes_status_accessibility_reload_telemetry)
        collapsed = stringResource(Res.string.chats_message_action_collapsed)
        expanded = stringResource(Res.string.chats_message_action_expanded)
        contactInfo = stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info)
        contactEditor = stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info_placeholder)
        security = stringResource(Res.string.remotenodes_remote_nodes_settings_security)
        actions = stringResource(Res.string.remotenodes_remote_nodes_settings_device_actions)
        password = stringResource(Res.string.remotenodes_remote_nodes_settings_new_password)
        sendAdvert = stringResource(Res.string.remotenodes_remote_nodes_settings_send_advert)
        telemetryTab = stringResource(Res.string.remotenodes_remote_nodes_settings_tab_telemetry)
        history = stringResource(Res.string.remotenodes_remote_nodes_history_title)
        voltage = stringResource(Res.string.remotenodes_remote_nodes_status_sensor_voltage)
    }
}
