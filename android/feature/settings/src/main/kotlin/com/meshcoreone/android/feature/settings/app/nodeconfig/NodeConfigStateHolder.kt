// PortedFrom: MC1/Views/Settings/NodeConfig/NodeConfigImportViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/NodeConfig/NodeConfigExportViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.app.nodeconfig

import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NodeConfigImportState(
    val config: MeshCoreNodeConfig? = null,
    val current: MeshCoreNodeConfig? = null,
    val sections: ConfigSections = ConfigSections(),
    val parsing: Boolean = false,
    val planning: Boolean = false,
    val applying: Boolean = false,
    val complete: Boolean = false,
    val confirmation: Boolean = false,
    val channelsOverwrite: Boolean = false,
    val deviceName: String = "",
    val progress: NodeConfigProgress? = null,
    val error: String? = null,
) {
    val busy get() = parsing || planning || applying || complete
    val overwrite get() = sections.nodeIdentity || sections.radioSettings || sections.positionSettings ||
        sections.otherSettings || (sections.channels && channelsOverwrite)
    val additive get() = sections.channels || sections.contacts
}

data class NodeConfigExportState(
    val sections: ConfigSections = ConfigSections(),
    val busy: Boolean = false,
    val error: String? = null,
)

/** Owned by the process Settings adapter, so leaving a screen cannot cancel destructive writes. */
class NodeConfigStateHolder(
    private val scope: CoroutineScope,
    private val session: () -> NodeConfigSession,
    private val codec: NodeConfigCodec,
    private val messages: NodeConfigMessages,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val importing = MutableStateFlow(NodeConfigImportState())
    val importState = importing.asStateFlow()
    private val exporting = MutableStateFlow(NodeConfigExportState())
    val exportState = exporting.asStateFlow()
    private var parseId = 0L
    private var parseJob: Job? = null
    private var previewJob: Job? = null
    private var applyJob: Job? = null
    private var captured: NodeConfigSession? = null
    private var didProgress = false

    fun selectImport(sections: ConfigSections) {
        if (!importing.value.busy && !importing.value.confirmation) importing.value = importing.value.copy(sections = sections)
    }

    fun selectExport(sections: ConfigSections) {
        if (!exporting.value.busy) exporting.value = exporting.value.copy(sections = sections)
    }

    fun export(documents: NodeConfigDocuments) {
        val state = exporting.value
        if (state.busy || !state.sections.anySectionSelected) return
        exporting.value = state.copy(busy = true, error = null)
        scope.launch {
            try {
                val connection = session()
                val config = connection.export(state.sections)
                connection.requireCurrent()
                val bytes = withContext(io) { codec.encode(config) }
                documents.save(exportFileName(connection.deviceName, config.name, now(), zone()), bytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                exporting.value = exporting.value.copy(error = messages.error(failure))
            } finally {
                exporting.value = exporting.value.copy(busy = false)
            }
        }
    }

    fun pick(documents: NodeConfigDocuments) {
        if (importing.value.busy || importing.value.confirmation) return
        parseJob?.cancel()
        val id = ++parseId
        importing.value = NodeConfigImportState(parsing = true)
        parseJob = scope.launch {
            try {
                val connection = session()
                val bytes = documents.pick()
                if (id != parseId) return@launch
                if (bytes == null) {
                    importing.value = NodeConfigImportState()
                    return@launch
                }
                val config = withContext(io) { codec.decode(bytes) }
                connection.requireCurrent()
                val current = try {
                    connection.current()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    connection.requireCurrent()
                    if (id == parseId) {
                        captured = connection
                        importing.value = NodeConfigImportState(
                            config = config,
                            deviceName = connection.deviceName,
                            sections = selectedSections(config),
                            error = messages.error(failure),
                        )
                    }
                    return@launch
                }
                if (id != parseId) return@launch
                captured = connection
                importing.value = NodeConfigImportState(
                    config = config, current = current, deviceName = connection.deviceName,
                    sections = selectedSections(config),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (id == parseId) importing.value = NodeConfigImportState(error = messages.error(failure))
            }
        }
    }

    fun prepareConfirmation() {
        val state = importing.value
        val config = state.config ?: return
        if (state.busy || state.confirmation || !state.sections.anySectionSelected) return
        val id = parseId
        importing.value = state.copy(planning = true, error = null)
        previewJob = scope.launch {
            try {
                val connection = requireNotNull(captured)
                connection.requireCurrent()
                val overwrite = connection.preview(config, state.sections)
                connection.requireCurrent()
                if (id != parseId) return@launch
                importing.value = importing.value.copy(planning = false, confirmation = true, channelsOverwrite = overwrite)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (id == parseId) importing.value = importing.value.copy(planning = false, error = messages.error(failure))
            }
        }
    }

    fun dismissConfirmation() { importing.value = importing.value.copy(confirmation = false) }

    fun apply() {
        val state = importing.value
        val config = state.config ?: return
        if (!state.confirmation || state.busy) return
        didProgress = false
        importing.value = state.copy(confirmation = false, applying = true, progress = null, error = null)
        applyJob = scope.launch {
            val connection: NodeConfigSession
            try {
                connection = requireNotNull(captured)
                connection.requireCurrent()
                connection.apply(config, state.sections) { progress ->
                    didProgress = true
                    importing.value = importing.value.copy(progress = progress)
                }
                connection.requireCurrent()
            } catch (cancelled: CancellationException) {
                importing.value = importing.value.copy(applying = false, complete = false, error = messages.cancelled(didProgress))
                throw cancelled
            } catch (failure: Exception) {
                importing.value = importing.value.copy(applying = false, error = messages.failed(failure, didProgress))
                return@launch
            }

            // The destructive apply has committed; refresh is best-effort like the source workflow.
            try {
                connection.refresh()
            } catch (_: CancellationException) {
            } catch (_: Exception) {
            }
            importing.value = importing.value.copy(applying = false, complete = true)
            try {
                sleep(1500)
            } catch (_: CancellationException) {
                return@launch
            }
            reset()
        }
    }

    fun cancelImport() { if (importing.value.applying) applyJob?.cancel() }

    fun handleDismissal() {
        ++parseId
        parseJob?.cancel()
        previewJob?.cancel()
        if (!importing.value.applying) reset()
    }

    private fun reset() {
        captured = null
        importing.value = NodeConfigImportState()
    }

    private fun selectedSections(config: MeshCoreNodeConfig) = ConfigSections(
        nodeIdentity = config.name != null || config.publicKey != null || config.privateKey != null,
        radioSettings = config.radioSettings != null,
        positionSettings = config.positionSettings?.isZero == false,
        otherSettings = config.otherSettings != null,
        channels = config.channels != null,
        contacts = config.contacts != null,
    )
}

fun exportFileName(deviceName: String?, configName: String?, instant: Instant, zone: ZoneId): String {
    val name = (deviceName ?: configName ?: "unknown").replace(Regex("[^a-zA-Z0-9_-]"), "_").trim('_')
    val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.US).withZone(zone).format(instant)
    return "${name}_meshcore_config_$timestamp.json"
}
