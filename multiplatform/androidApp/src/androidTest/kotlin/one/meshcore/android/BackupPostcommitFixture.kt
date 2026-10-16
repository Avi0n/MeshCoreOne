package one.meshcore.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.settings.ui.BackupSettingsScreen
import one.meshcore.shared.settings.ui.BackupSettingsWorkspace
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

internal class BackupPostcommitFixture(stage: BackupPostcommitGate.Stage, private val name: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val gate = BackupPostcommitGate(stage)
    val graph = BackupPostcommitGraph(gate)
    val ui = BackupPostcommitUi()
    private val workspaceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineName(BackupPostcommitGate.MARKER),
    )
    private val workspace = BackupSettingsWorkspace(workspaceScope)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val output = File(instrumentation.targetContext.getExternalFilesDir(null), "backup-postcommit")
    var result: BackupImportResult? = null
        private set
    var enteredAppImport = false
        private set
    var calls = 0
        private set
    private var workspaceStopped = false

    fun open() {
        runBlocking { ui.loadLabels() }
        graph.open()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val application = instrumentation.targetContext.applicationContext as MeshCoreApplication
        runBlocking {
            withTimeout(BackupPostcommitGraph.TIMEOUT_MS) { application.container.awaitInitialOfflineData() }
        }
        ui.await("Ordinary initial AppRoot rendered before fixture mount") { ui.visible(ui.welcome) }
        runBlocking {
            assertEquals(null, application.container.lastConnectionStore.deviceId())
            assertFalse(application.container.connectionManager.state.value.isConnected)
            BackupPostcommitRows(application.container.database).assertCounts(
                BackupPostcommitRows(application.container.database).snapshot(),
                copies = 0,
            )
        }
        check(output.mkdirs() || output.isDirectory)
        scenario?.onActivity { activity ->
            activity.setContent {
                TimestampFormatting {
                    MaterialTheme {
                        BackupSettingsScreen(
                            isRadioConnected = false,
                            workspace = workspace,
                            onImportFile = { graph.json },
                            onImportConfirmed = { json ->
                                calls += 1
                                gate.origin.complete(currentCoroutineContext().job)
                                try {
                                    gate.hold(BackupPostcommitGate.Stage.BEFORE)
                                    enteredAppImport = true
                                    graph.container.importBackupJson(json).also { result = it }
                                } finally {
                                    gate.callbackReturned.complete(Unit)
                                }
                            },
                        )
                    }
                }
            }
        }
        ui.await("Actual Backup screen mounted") { ui.visible(ui.importFile) }
    }

    fun start() {
        ui.click(ui.importFile)
        ui.await("Actual preview shown") { ui.visible(ui.preview) }
        ui.click(ui.confirm)
        runBlocking { withTimeout(BackupPostcommitGraph.TIMEOUT_MS) { gate.entered.await() } }
        assertEquals(1, calls)
        assertEquals(1, gate.calls)
        assertTrue(runBlocking { gate.origin.await().isActive })
    }

    fun cancel() {
        ui.click(ui.cancel)
        ui.await("Actual UI Cancel targets captured importing Job") {
            gate.origin.isCompleted && runBlocking { gate.origin.await().isCancelled }
        }
    }

    fun settle() {
        gate.release.complete(Unit)
        runBlocking {
            withTimeout(BackupPostcommitGraph.TIMEOUT_MS) {
                gate.origin.await().join()
                gate.returned.await()
                gate.callbackReturned.await()
            }
        }
        ui.await("Backup reaches success or cancellation destination") {
            ui.visible(ui.success) ||
                ui.visible(ui.cancelled)
        }
    }

    fun record(outcome: Boolean, committed: Boolean, published: Boolean) {
        val receipt = JSONObject().put("outcome", outcome).put("committed", committed)
            .put("publishedDiscover", published).put("originCancelled", runBlocking { gate.origin.await().isCancelled })
            .put("originCompleted", runBlocking { gate.origin.await().isCompleted })
            .put("gateReturned", gate.returned.isCompleted).put("callbackReturned", gate.callbackReturned.isCompleted)
            .put("oneImportCallback", calls == 1).put("oneGate", gate.calls == 1)
            .put("returnedAppResult", result != null).put("enteredAppImport", enteredAppImport)
            .put("successVisible", ui.visible(ui.success)).put("cancelledVisible", ui.visible(ui.cancelled))
        File(output, "$name.json").writeText(receipt.toString())
        ui.screenshot(File(output, "$name.png"))
    }

    fun close() {
        gate.release.complete(Unit)
        try {
            stopUiAndOperation()
        } finally {
            try {
                graph.close()
            } finally {
                check(output.mkdirs() || output.isDirectory)
                File(output, "$name-teardown.json").writeText(
                    graph.teardown().put("workspaceStopped", workspaceStopped)
                        .put("activityClosed", scenario == null).toString(),
                )
            }
        }
    }

    private fun stopUiAndOperation() {
        try {
            scenario?.onActivity { it.setContent {} }
        } finally {
            try {
                stopOwnedWorkspace()
            } finally {
                scenario?.close()
                scenario = null
            }
        }
    }

    private fun stopOwnedWorkspace() {
        try {
            runBlocking {
                withTimeout(BackupPostcommitGraph.TIMEOUT_MS) {
                    if (gate.origin.isCompleted) gate.origin.await().cancelAndJoin()
                }
            }
        } finally {
            runBlocking {
                withTimeout(BackupPostcommitGraph.TIMEOUT_MS) { workspaceScope.coroutineContext.job.cancelAndJoin() }
            }
            workspaceStopped = workspaceScope.coroutineContext.job.isCompleted
        }
    }
}
