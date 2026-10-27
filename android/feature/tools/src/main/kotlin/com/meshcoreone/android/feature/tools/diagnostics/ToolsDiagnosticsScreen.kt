// PortedFrom: MC1/Views/Tools/CLI/CLIToolView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/CLI/CLITerminalView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/RxLogView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/NoiseFloorView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliOutputLine
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliOutputType
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliTerminalController
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliTerminalKey
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliToolStateHolder
import com.meshcoreone.android.feature.tools.diagnostics.noisefloor.NoiseFloorPresentation
import com.meshcoreone.android.feature.tools.diagnostics.noisefloor.NoiseFloorScreenMode
import com.meshcoreone.android.feature.tools.diagnostics.noisefloor.NoiseFloorState
import com.meshcoreone.android.feature.tools.diagnostics.noisefloor.NoiseFloorStateHolder
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogDecryptFilter
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogExport
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogListState
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogPresentation
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogRouteFilter
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogStateHolder
import com.meshcoreone.android.feature.tools.navigation.ToolSelection
import kotlinx.coroutines.launch

private enum class DiagnosticsPage(val title: Int) {
    CLI(AppToolsStrings.toolsCli),
    RX_LOG(AppToolsStrings.toolsRxLog),
    NOISE_FLOOR(AppToolsStrings.toolsNoiseFloor),
}

@Composable
fun ToolsDiagnosticsScreen(
    dependencies: ToolsDiagnosticsDependencies,
    selection: ToolSelection = ToolSelection.CLI,
    modifier: Modifier = Modifier,
) {
    val initialPage = when (selection) {
        ToolSelection.RX_LOG -> DiagnosticsPage.RX_LOG
        ToolSelection.NOISE_FLOOR -> DiagnosticsPage.NOISE_FLOOR
        else -> DiagnosticsPage.CLI
    }
    var selected by rememberSaveable(initialPage) { mutableStateOf(initialPage) }
    val connectionVersion by dependencies.connectionVersion.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DiagnosticsPage.entries.forEach { page ->
                FilterChip(
                    selected = selected == page,
                    onClick = { selected = page },
                    label = { Text(stringResource(page.title)) },
                    modifier = Modifier.testTag("diagnostics-page:${page.name}"),
                )
            }
        }
        HorizontalDivider()
        when (selected) {
            DiagnosticsPage.CLI -> CliScreen(dependencies, connectionVersion, Modifier.fillMaxSize())
            DiagnosticsPage.RX_LOG -> RxLogScreen(dependencies, connectionVersion, Modifier.fillMaxSize())
            DiagnosticsPage.NOISE_FLOOR -> NoiseFloorScreen(dependencies, connectionVersion, Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CliScreen(dependencies: ToolsDiagnosticsDependencies, connectionVersion: Int, modifier: Modifier) {
    val hasCliService = dependencies.cli.repeaterAdminService() != null
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val text = remember(resources) { ResourcesDiagnosticsText(resources) }
    val holder = remember { CliToolStateHolder(scope, text, errors = dependencies.cliErrors) }
    val controller = remember(holder) { CliTerminalController(holder, scope) }
    val state by holder.state.collectAsStateWithLifecycle()
    val uiState by controller.state.collectAsStateWithLifecycle()
    var field by remember { mutableStateOf(TextFieldValue()) }
    val focus = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current

    DisposableEffect(holder) {
        onDispose(holder::cleanup)
    }
    DisposableEffect(controller, hasCliService) {
        if (hasCliService) controller.onAppear()
        onDispose(controller::onDisappear)
    }
    LaunchedEffect(dependencies, connectionVersion) {
        if (connectionVersion > 0) holder.reset()
        holder.configure(dependencies.cli, dependencies.cli.connectedDevice()?.nodeName ?: text.string(AppToolsStrings.toolsCliDefaultDevice))
    }
    LaunchedEffect(state.terminal.currentInput, uiState.cursorPosition) {
        val selection = uiState.cursorPosition.coerceIn(0, state.terminal.currentInput.length)
        if (field.text != state.terminal.currentInput || field.selection.end != selection) {
            field = TextFieldValue(state.terminal.currentInput, TextRange(selection))
        }
    }
    LaunchedEffect(state.isWaitingForResponse) { controller.onWaitingChanged(state.isWaitingForResponse) }
    LaunchedEffect(uiState.isKeyboardFocused, hasCliService) {
        if (hasCliService && uiState.isKeyboardFocused) focus.requestFocus()
    }

    if (!hasCliService) {
        Column(modifier.verticalScroll(rememberScrollState())) {
            DiagnosticMessage(
                stringResource(AppToolsStrings.toolsCliNotConnected),
                stringResource(AppToolsStrings.toolsCliNotConnectedDescription),
            )
        }
        return
    }

    Column(
        modifier.padding(horizontal = 12.dp).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionUp -> controller.onKey(CliTerminalKey.UP)
                Key.DirectionDown -> controller.onKey(CliTerminalKey.DOWN)
                Key.Tab -> controller.onKey(CliTerminalKey.TAB)
                Key.Escape -> controller.onKey(CliTerminalKey.ESCAPE)
                Key.K -> controller.onKey(CliTerminalKey.K, event.isCtrlPressed)
                else -> false
            }
        },
    ) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("cli-output"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.terminal.outputLines, key = CliOutputLine::id) { line ->
                Text(
                    line.text,
                    color = when (line.type) {
                        CliOutputType.COMMAND -> MaterialTheme.colorScheme.onSurfaceVariant
                        CliOutputType.SUCCESS -> MaterialTheme.colorScheme.primary
                        CliOutputType.ERROR -> MaterialTheme.colorScheme.error
                        CliOutputType.RESPONSE -> MaterialTheme.colorScheme.onSurface
                    },
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
        }
        state.terminal.tabSuggestions?.let { suggestions ->
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { suggestion ->
                    AssistChip(
                        onClick = {
                            if (holder.applySuggestion(suggestion)) {
                                controller.onSelectionChanged(holder.currentInput.length)
                            }
                        },
                        label = { Text(suggestion) },
                    )
                }
            }
        }
        Text(
            holder.promptText,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedTextField(
            value = field,
            onValueChange = {
                field = it
                controller.onTextChanged(it.text, it.selection.end)
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("cli-input"),
            label = { Text(stringResource(AppToolsStrings.toolsCliCommandInput)) },
            visualTransformation = if (state.pendingLoginContact != null) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (state.pendingLoginContact != null) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { controller.onSubmit() }),
            singleLine = true,
            supportingText = state.terminal.ghostText.takeIf(String::isNotEmpty)?.let { ghost -> ({ Text(field.text + ghost) }) },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(controller::onHistoryUp) { Text(stringResource(AppToolsStrings.toolsCliHistoryUp)) }
            TextButton(controller::onHistoryDown) { Text(stringResource(AppToolsStrings.toolsCliHistoryDown)) }
            TextButton(controller::onTabComplete) { Text(stringResource(AppToolsStrings.toolsCliTabComplete)) }
            TextButton({ controller.onPaste(clipboard.getText()?.text) }) { Text(stringResource(AppToolsStrings.toolsCliPaste)) }
            if (uiState.showCancel) {
                Button(controller::onCancel) { Text(stringResource(AppToolsStrings.toolsCliCancelOperation)) }
            }
        }
    }
}

@Composable
private fun RxLogScreen(dependencies: ToolsDiagnosticsDependencies, connectionVersion: Int, modifier: Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val text = remember(resources) { ResourcesDiagnosticsText(resources) }
    val holder = remember { RxLogStateHolder(scope) }
    val presentation = remember(text) { RxLogPresentation(text) }
    val state by holder.state.collectAsStateWithLifecycle()
    var listState by remember { mutableStateOf(RxLogListState()) }
    var confirmClear by remember { mutableStateOf(false) }
    val connected = dependencies.isConnected()
    val shareLabel = stringResource(AppChatsStrings.chatsImageViewerShare)

    LaunchedEffect(dependencies, connectionVersion) {
        holder.configure(dependencies.rxLog)
        holder.subscribe()
        holder.loadNodeNames()
    }
    DisposableEffect(holder) { onDispose(holder::unsubscribe) }

    Column(modifier.padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(presentation.liveLabel(connected), style = MaterialTheme.typography.titleMedium)
                Text(presentation.headerCount(state), style = MaterialTheme.typography.bodySmall)
            }
            Row {
                TextButton({
                    val payload = RxLogExport.csv(state.filteredEntries)
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_TEXT, payload),
                            shareLabel,
                        ),
                    )
                }, enabled = state.filteredEntries.isNotEmpty()) {
                    Text(shareLabel)
                }
                TextButton({ confirmClear = true }, enabled = state.entries.isNotEmpty()) {
                    Text(stringResource(AppToolsStrings.toolsRxLogDelete))
                }
            }
        }
        FilterRows(state.routeFilter, state.decryptFilter, listState, holder, { listState = it })
        val entries = listState.displayEntries(state)
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    stringResource(if (connected) AppToolsStrings.toolsRxLogListeningDescription else AppToolsStrings.toolsRxLogNotConnectedDescription),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { entry ->
                    val row = presentation.row(
                        entry,
                        listState.groupCount(state, entry),
                        dependencies.localPublicKeyPrefix(),
                        state.nodeNames,
                    )
                    Card(
                        onClick = {
                            listState = listState.settingExpanded(entry.packetHash, !listState.isExpanded(entry.packetHash))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(row.routeLabel, style = MaterialTheme.typography.labelLarge)
                                Text(row.snr.orEmpty(), style = MaterialTheme.typography.labelMedium)
                            }
                            Text(row.path, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            row.fromTo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(row.messagePreview ?: row.packetSummary, style = MaterialTheme.typography.bodyMedium)
                            row.groupBadge?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                            if (listState.isExpanded(entry.packetHash)) {
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                row.details.forEach { detail ->
                                    Text("${detail.label}: ${detail.value}", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "${stringResource(AppToolsStrings.toolsRxLogRawPayload)}: ${row.rawPayloadHex}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(AppToolsStrings.toolsRxLogDeleteLogs)) },
            text = { Text(stringResource(AppToolsStrings.toolsRxLogDeleteConfirmation)) },
            confirmButton = {
                TextButton({
                    confirmClear = false
                    scope.launch {
                        holder.clearLog()
                        listState = listState.cleared()
                    }
                }) { Text(stringResource(AppLocalizableStrings.commonDelete)) }
            },
            dismissButton = {
                TextButton({ confirmClear = false }) { Text(stringResource(AppLocalizableStrings.commonCancel)) }
            },
        )
    }
}

@Composable
private fun FilterRows(
    route: RxLogRouteFilter,
    decrypt: RxLogDecryptFilter,
    listState: RxLogListState,
    holder: RxLogStateHolder,
    updateList: (RxLogListState) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RxLogRouteFilter.entries.forEach {
                FilterChip(it == route, { holder.setRouteFilter(it) }, { Text(stringResource(it.labelId)) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RxLogDecryptFilter.entries.forEach {
                FilterChip(it == decrypt, { holder.setDecryptFilter(it) }, { Text(stringResource(it.labelId)) })
            }
            FilterChip(
                selected = listState.groupDuplicates,
                onClick = { updateList(listState.togglingGroupDuplicates()) },
                label = { Text(stringResource(AppToolsStrings.toolsRxLogGroupDuplicates)) },
            )
        }
    }
}

@Composable
private fun NoiseFloorScreen(dependencies: ToolsDiagnosticsDependencies, connectionVersion: Int, modifier: Modifier) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val text = remember(resources) { ResourcesDiagnosticsText(resources) }
    val holder = remember { NoiseFloorStateHolder(scope, text) }
    val state by holder.state.collectAsStateWithLifecycle()
    val connected = dependencies.isConnected()
    var selectedReadingId by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(holder, connected, connectionVersion) {
        if (connected) holder.startPolling(dependencies.radioStats) else holder.stopPolling()
        onDispose(holder::stopPolling)
    }
    LaunchedEffect(state.readings) {
        if (selectedReadingId != null && state.readings.none { it.id.toString() == selectedReadingId }) {
            selectedReadingId = null
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (NoiseFloorPresentation.mode(connected, state)) {
            NoiseFloorScreenMode.DISCONNECTED -> DiagnosticMessage(
                stringResource(AppToolsStrings.toolsNoiseFloor),
                stringResource(AppToolsStrings.toolsNoiseFloorNotConnectedDescription),
            )
            NoiseFloorScreenMode.COLLECTING -> {
                CircularProgressIndicator()
                DiagnosticMessage(
                    stringResource(AppToolsStrings.toolsNoiseFloorCollectingData),
                    state.errorMessage ?: stringResource(AppToolsStrings.toolsNoiseFloorCollectingDataDescription),
                )
            }
            NoiseFloorScreenMode.CONTENT -> {
                CurrentNoiseCard(state, text)
                val selectedIndex = state.readings.indexOfFirst { it.id.toString() == selectedReadingId }
                NoiseChart(state, text, selectedIndex) { index ->
                    selectedReadingId = state.readings.getOrNull(index)?.id?.toString()
                }
                NoiseStatistics(state, text)
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun CurrentNoiseCard(state: NoiseFloorState, text: DiagnosticsText) {
    Card(
        Modifier.fillMaxWidth().semantics {
            contentDescription = NoiseFloorPresentation.currentReadingAccessibilityLabel(state, text)
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${NoiseFloorPresentation.currentValueText(state, text)} ${stringResource(AppToolsStrings.toolsNoiseFloorDBm)}",
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(stringResource(state.qualityLevel.labelId), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun NoiseChart(
    state: NoiseFloorState,
    text: DiagnosticsText,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    val readings = state.readings
    val start = readings.first().timestamp
    val points = NoiseFloorPresentation.points(readings, start)
    val domain = NoiseFloorPresentation.xDomain(readings, start)
    val lineColor = MaterialTheme.colorScheme.primary
    val selected = selectedIndex.takeIf { it in readings.indices }?.let(readings::get)
    Column {
        Text(
            stringResource(AppToolsStrings.toolsNoiseFloor),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Surface(
            Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 220.dp).focusable().testTag("noise-floor-chart")
                .semantics { contentDescription = NoiseFloorPresentation.chartAccessibilityLabel(state, text) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> true.also { onSelected((if (selectedIndex < 0) readings.lastIndex else selectedIndex - 1).coerceAtLeast(0)) }
                        Key.DirectionRight -> true.also { onSelected((selectedIndex + 1).coerceAtMost(readings.lastIndex)) }
                        Key.Escape -> true.also { onSelected(-1) }
                        else -> false
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val width = size.width
                val height = size.height
                fun x(value: Double) = ((value - domain.start) / (domain.endInclusive - domain.start)).toFloat() * width
                fun y(value: Short) = ((NoiseFloorPresentation.Y_DOMAIN_MAX - value) /
                    (NoiseFloorPresentation.Y_DOMAIN_MAX - NoiseFloorPresentation.Y_DOMAIN_MIN)).toFloat() * height
                val visible = points.filter { it.elapsedSeconds in domain }
                if (visible.isNotEmpty()) {
                    val path = Path().apply {
                        moveTo(x(visible.first().elapsedSeconds), y(visible.first().noiseFloor))
                        visible.drop(1).forEach { lineTo(x(it.elapsedSeconds), y(it.noiseFloor)) }
                    }
                    drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx()))
                }
                selected?.let {
                    val elapsed = NoiseFloorPresentation.elapsedSeconds(it.timestamp, start)
                    if (elapsed in domain) drawCircle(Color.White, 6.dp.toPx(), Offset(x(elapsed), y(it.noiseFloor)))
                }
            }
        }
        Text(
            selected?.let { "${it.noiseFloor} dBm, RSSI ${it.lastRSSI} dBm, SNR ${NoiseFloorPresentation.oneDecimalText(it.lastSNR, text)} dB" }
                ?: NoiseFloorPresentation.chartAccessibilityLabel(state, text),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NoiseStatistics(state: NoiseFloorState, text: DiagnosticsText) {
    val stats = state.statistics ?: return
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(AppToolsStrings.toolsNoiseFloorStatistics),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text("${stringResource(AppToolsStrings.toolsNoiseFloorMinimum)}: ${stats.min} dBm")
        Text("${stringResource(AppToolsStrings.toolsNoiseFloorMaximum)}: ${stats.max} dBm")
        Text("${stringResource(AppToolsStrings.toolsNoiseFloorAverage)}: ${NoiseFloorPresentation.oneDecimalText(stats.average, text)} dBm")
        Text(NoiseFloorPresentation.trendDescription(state.readings, text))
    }
}

@Composable
private fun DiagnosticMessage(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(body)
    }
}
