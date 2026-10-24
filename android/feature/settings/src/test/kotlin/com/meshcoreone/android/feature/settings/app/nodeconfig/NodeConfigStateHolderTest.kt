// PortedFrom: MC1Tests/ViewModels/NodeConfigImportViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.app.nodeconfig

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.feature.settings.app.support.OriginalCase
import com.meshcoreone.android.feature.settings.device.support.TestScheduler
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.*
import org.junit.Test

class NodeConfigStateHolderTest {
    private class TestRig {
        val scheduler = TestScheduler()
        val backgroundScope = scheduler.scope
        fun runCurrent() = scheduler.runCurrent()
        fun advanceTimeBy(millis: Long) = scheduler.advanceBy(millis.milliseconds)
    }
    private fun runTest(block: TestRig.() -> Unit) {
        val rig = TestRig()
        try { rig.block() } finally { rig.backgroundScope.cancel(); rig.runCurrent() }
    }
    private class Radio : NodeConfigSession {
        override val deviceName = "Test radio"
        var valid = true
        var writes = 0
        var refreshes = 0
        var refreshAction: suspend () -> Unit = { refreshes++ }
        var overwrite = false
        var currentFailure: Exception? = null
        var preview: suspend () -> Unit = {}
        var execute: suspend ((NodeConfigProgress) -> Unit) -> Unit = { writes++ }
        override fun requireCurrent() { check(valid) { "connection changed" } }
        override suspend fun export(sections: ConfigSections) = MeshCoreNodeConfig(name = "Exported")
        override suspend fun current(): MeshCoreNodeConfig {
            currentFailure?.let { throw it }
            return MeshCoreNodeConfig(name = "Current")
        }
        override suspend fun preview(config: MeshCoreNodeConfig, sections: ConfigSections): Boolean { preview(); return overwrite }
        override suspend fun apply(config: MeshCoreNodeConfig, sections: ConfigSections, progress: (NodeConfigProgress) -> Unit) = execute(progress)
        override suspend fun refresh() = refreshAction()
    }
    private class Documents : NodeConfigDocuments {
        var read: suspend () -> ByteArray? = { byteArrayOf(1) }
        var saveResult = true
        var savedName: String? = null
        var savedBytes: ByteArray? = null
        override suspend fun pick() = read()
        override suspend fun save(name: String, bytes: ByteArray): Boolean {
            savedName = name; savedBytes = bytes
            return saveResult
        }
    }
    private val messages = object : NodeConfigMessages {
        override fun error(failure: Throwable) = failure.message ?: failure.toString()
        override fun cancelled(partial: Boolean) = "cancelled:$partial"
        override fun failed(failure: Throwable, partial: Boolean) = "failed:$partial:${error(failure)}"
    }
    private class Codec : NodeConfigCodec {
        var config = MeshCoreNodeConfig(name = "Imported", positionSettings = MeshCoreNodeConfig.PositionSettings("0", "0"),
            channels = emptyList<MeshCoreNodeConfig.ChannelConfig>().snapshot())
        var failure: Exception? = null
        override fun decode(bytes: ByteArray): MeshCoreNodeConfig { failure?.let { throw it }; return config }
        override fun encode(config: MeshCoreNodeConfig) = byteArrayOf(7, 8)
    }
    private fun TestRig.holder(radio: Radio, codec: Codec = Codec()) =
        NodeConfigStateHolder(backgroundScope, { radio.requireCurrent(); radio }, codec, messages,
            Dispatchers.Unconfined, sleep = { scheduler.clock.sleep(it.milliseconds) })

    @Test
    @OriginalCase("NodeConfigImportViewModelTests::Leaving the screen resets a loaded preview back to the file picker()")
    fun leavingPreviewResetsAllState() = runTest {
        val h = holder(Radio())
        h.pick(Documents()); runCurrent()
        assertNotNull(h.importState.value.config)
        assertFalse(h.importState.value.sections.positionSettings)
        assertTrue(h.importState.value.sections.channels)
        h.prepareConfirmation(); runCurrent()
        assertTrue(h.importState.value.confirmation)
        h.handleDismissal()
        assertEquals(NodeConfigImportState(), h.importState.value)
    }

    @Test
    @OriginalCase("NodeConfigImportViewModelTests::Leaving the screen mid-import preserves the in-flight import state()")
    fun inFlightImportSurvivesLeavingAndFinishesOnce() = runTest {
        val radio = Radio()
        val release = CompletableDeferred<Unit>()
        radio.execute = { progress -> radio.writes++; progress(NodeConfigProgress(1, 2, "identity")); release.await() }
        val h = holder(radio)
        h.pick(Documents()); runCurrent(); h.prepareConfirmation(); runCurrent(); h.apply(); runCurrent()
        h.handleDismissal()
        assertTrue(h.importState.value.applying)
        assertEquals(1, h.importState.value.progress?.current)
        h.apply()
        release.complete(Unit); runCurrent()
        assertEquals(1, radio.writes)
        assertEquals(1, radio.refreshes)
        assertTrue(h.importState.value.complete)
        advanceTimeBy(1500); runCurrent()
        assertEquals(NodeConfigImportState(), h.importState.value)
    }

    @Test fun leavingCompletionWindowResetsToFilePicker() = runTest {
        val h = holder(Radio())
        h.pick(Documents()); runCurrent(); h.prepareConfirmation(); runCurrent(); h.apply(); runCurrent()
        assertTrue(h.importState.value.complete)
        h.handleDismissal()
        assertEquals(NodeConfigImportState(), h.importState.value)
    }

    @Test
    @OriginalCase("NodeConfigImportViewModelTests::A cancellation message distinguishes a partial cancel from a clean one()")
    fun cancellationBeforeAndAfterProgressIsDistinct() = runTest {
        for (partial in listOf(false, true)) {
            val radio = Radio()
            radio.execute = { progress ->
                if (partial) progress(NodeConfigProgress(1, 2, "identity"))
                awaitCancellation()
            }
            val h = holder(radio)
            h.pick(Documents()); runCurrent(); h.prepareConfirmation(); runCurrent(); h.apply(); runCurrent()
            h.cancelImport(); runCurrent()
            assertEquals("cancelled:$partial", h.importState.value.error)
            assertFalse(h.importState.value.applying)
        }
    }

    @Test
    @OriginalCase("NodeConfigImportViewModelTests::A failure message wraps the localized description only when a write already landed()")
    fun failureBeforeAndAfterProgressIsDistinct() = runTest {
        for (partial in listOf(false, true)) {
            val radio = Radio()
            radio.execute = { progress ->
                if (partial) progress(NodeConfigProgress(1, 2, "identity"))
                throw IOException("fault")
            }
            val h = holder(radio)
            h.pick(Documents()); runCurrent(); h.prepareConfirmation(); runCurrent(); h.apply(); runCurrent()
            assertEquals("failed:$partial:fault", h.importState.value.error)
            assertFalse(h.importState.value.complete)
        }
    }

    @Test fun refreshFailureDoesNotUndoCommittedImport() = runTest {
        val radio = Radio().also {
            it.refreshAction = { it.refreshes++; throw IOException("refresh failed") }
        }
        val h = holder(radio)
        h.pick(Documents()); runCurrent(); h.prepareConfirmation(); runCurrent(); h.apply(); runCurrent()
        assertEquals(1, radio.writes)
        assertEquals(1, radio.refreshes)
        assertTrue(h.importState.value.complete)
        assertFalse(h.importState.value.applying)
        assertNull(h.importState.value.error)
    }

    @Test fun cancellationDuringBestEffortRefreshKeepsCommittedImportComplete() = runTest {
        val radio = Radio().also {
            it.refreshAction = { it.refreshes++; awaitCancellation() }
        }
        val h = holder(radio)
        h.pick(Documents()); runCurrent(); h.prepareConfirmation(); runCurrent(); h.apply(); runCurrent()
        assertTrue(h.importState.value.applying)
        h.cancelImport(); runCurrent()
        assertEquals(1, radio.writes)
        assertEquals(1, radio.refreshes)
        assertTrue(h.importState.value.complete)
        assertFalse(h.importState.value.applying)
        assertNull(h.importState.value.error)
    }

    @Test fun previewValidatesBeforeConfirmationAndLocksSelection() = runTest {
        val radio = Radio()
        val wait = CompletableDeferred<Unit>()
        radio.preview = { wait.await(); throw IOException("validation") }
        val h = holder(radio)
        h.pick(Documents()); runCurrent()
        val sections = h.importState.value.sections
        h.prepareConfirmation(); runCurrent()
        h.selectImport(ConfigSections())
        assertEquals(sections, h.importState.value.sections)
        wait.complete(Unit); runCurrent(); h.apply()
        assertEquals("validation", h.importState.value.error)
        assertFalse(h.importState.value.confirmation)
        assertEquals(0, radio.writes)
    }

    @Test fun dismissingPreviewCannotPresentLateConfirmation() = runTest {
        val radio = Radio()
        val wait = CompletableDeferred<Unit>()
        radio.preview = { withContext(NonCancellable) { wait.await() } }
        val h = holder(radio)
        h.pick(Documents()); runCurrent(); h.prepareConfirmation(); runCurrent()
        h.handleDismissal(); wait.complete(Unit); runCurrent()
        assertEquals(NodeConfigImportState(), h.importState.value)
    }

    @Test fun staleDocumentResultCannotReplaceNewParse() = runTest {
        val radio = Radio()
        val first = CompletableDeferred<ByteArray?>()
        val docs = Documents().also { it.read = { withContext(NonCancellable) { first.await() } } }
        val h = holder(radio)
        h.pick(docs); runCurrent(); h.handleDismissal()
        h.pick(Documents()); runCurrent()
        first.complete(null); runCurrent()
        assertEquals("Imported", h.importState.value.config?.name)
    }

    @Test fun cancellationParseAndProviderFailureNeverBecomeSuccess() = runTest {
        val radio = Radio()
        val codec = Codec()
        val h = holder(radio, codec)
        val docs = Documents().also { it.read = { null } }
        h.pick(docs); runCurrent()
        assertEquals(NodeConfigImportState(), h.importState.value)
        docs.read = { throw SecurityException("revoked") }
        h.pick(docs); runCurrent()
        assertEquals("revoked", h.importState.value.error)
        codec.failure = IOException("invalid JSON")
        h.pick(Documents()); runCurrent()
        assertEquals("invalid JSON", h.importState.value.error)
        assertEquals(0, radio.writes)
    }

    @Test fun currentDiffFailureIsVisibleWithoutDiscardingValidatedFile() = runTest {
        val radio = Radio().also { it.currentFailure = IOException("current read failed") }
        val h = holder(radio)
        h.pick(Documents()); runCurrent()
        assertEquals("Imported", h.importState.value.config?.name)
        assertNotNull(h.importState.value.error)
        assertTrue(h.importState.value.sections.nodeIdentity)
    }

    @Test fun radioReplacementAfterConfirmationCannotReceiveWrites() = runTest {
        val radio = Radio()
        val h = holder(radio)
        h.pick(Documents()); runCurrent(); h.prepareConfirmation(); runCurrent()
        radio.valid = false
        h.apply(); runCurrent()
        assertEquals(0, radio.writes)
        assertEquals("failed:false:connection changed", h.importState.value.error)
    }

    @Test fun channelReplacementUsesMixedConfirmationAndExportUsesSelectedBytes() = runTest {
        val radio = Radio().also { it.overwrite = true }
        val h = holder(radio)
        h.pick(Documents()); runCurrent(); h.prepareConfirmation(); runCurrent()
        assertTrue(h.importState.value.overwrite)
        assertTrue(h.importState.value.additive)
        val docs = Documents()
        h.export(docs); runCurrent()
        assertNull(docs.savedName)
        h.selectExport(ConfigSections(nodeIdentity = true))
        h.export(docs); runCurrent()
        assertArrayEquals(byteArrayOf(7, 8), docs.savedBytes)
        assertFalse(h.exportState.value.busy)
    }

    @Test fun filenameUsesSourceFallbackSanitationAndLocalTimestamp() {
        val instant = Instant.parse("2026-10-24T04:00:01Z")
        assertEquals("Test_Node_meshcore_config_2026-10-23-230001.json",
            exportFileName(" Test Node! ", null, instant, ZoneId.of("America/Chicago")))
        assertEquals("unknown_meshcore_config_2026-10-24-040001.json", exportFileName(null, null, instant, ZoneId.of("UTC")))
        assertEquals("_meshcore_config_2026-10-24-040001.json", exportFileName("!!!", "fallback", instant, ZoneId.of("UTC")))
    }
}
