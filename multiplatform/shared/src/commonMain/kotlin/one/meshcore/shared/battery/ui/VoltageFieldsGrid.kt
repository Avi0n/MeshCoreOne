package one.meshcore.shared.battery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import one.meshcore.shared.battery.BatteryOcvGrid
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_battery_curve_accessibility_voltage_hint
import one.meshcore.shared.resources.settings_battery_curve_accessibility_voltage_label
import one.meshcore.shared.resources.settings_battery_curve_accessibility_voltage_value
import one.meshcore.shared.resources.settings_battery_curve_m_v
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

private const val GRID_COLUMNS = 2
private const val FIELD_HORIZONTAL_GAPS = 2
private val FIELD_MIN_WIDTH = 120.dp
private val GRID_GAP = 8.dp
private val FIELD_GAP = 4.dp
private val CURSOR_CLEARANCE = 4.dp

/**
 * Millivolt editor for an 11-point OCV curve. Commits when focus leaves a changed field,
 * so mid-edit partial strings never reach persistence.
 */
@Composable
fun VoltageFieldsGrid(
    committedValues: List<Int>,
    enabled: Boolean,
    onCommit: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftTexts by remember {
        mutableStateOf(committedValues.map { it.toString() })
    }
    LaunchedEffect(committedValues) {
        draftTexts = committedValues.map { it.toString() }
    }

    val draftInts = draftTexts.map { it.toIntOrNull() }
    val displayValues = List(OCVPreset.POINT_COUNT) { index ->
        draftInts.getOrNull(index) ?: committedValues.getOrElse(index) { 0 }
    }

    val minimumCellWidth = minimumVoltageCellWidth()
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = GRID_GAP)) {
        val columns = if ((maxWidth - GRID_GAP) / GRID_COLUMNS >= minimumCellWidth) GRID_COLUMNS else 1
        val cellWidth = (maxWidth - GRID_GAP * (columns - 1)) / columns
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
            maxItemsInEachRow = columns,
        ) {
            repeat(OCVPreset.POINT_COUNT) { index ->
                key(index) {
                    VoltageField(
                        index = index,
                        text = draftTexts.getOrElse(index) { "" },
                        hasError = BatteryOcvGrid.fieldHasError(index, displayValues),
                        enabled = enabled,
                        onTextChange = { next ->
                            draftTexts = draftTexts.toMutableList().also {
                                while (it.size < OCVPreset.POINT_COUNT) it.add("")
                                it[index] = next.filter { ch -> ch.isDigit() }
                            }
                        },
                        onCommitField = {
                            val parsed = draftTexts.mapIndexed { i, text ->
                                text.toIntOrNull() ?: committedValues.getOrElse(i) { 0 }
                            }
                            if (parsed != committedValues) onCommit(parsed)
                        },
                        modifier = Modifier.width(cellWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun minimumVoltageCellWidth(): Dp {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val valueStyle = MaterialTheme.typography.bodyLarge
    val unit = stringResource(Res.string.settings_battery_curve_m_v)
    return remember(measurer, density, direction, labelStyle, valueStyle, unit) {
        val percentWidth = (0 until OCVPreset.POINT_COUNT).maxOf {
            measurer.measure("${BatteryOcvGrid.percentAtIndex(it)}%", labelStyle).size.width
        }
        val unitWidth = measurer.measure(unit, labelStyle).size.width
        val digitCount = OCVPreset.VALID_MILLIVOLT_RANGE.last.toString().length
        val valueWidth = ('0'..'9').maxOf {
            measurer.measure(it.toString().repeat(digitCount), valueStyle).size.width
        }
        val contentPadding = OutlinedTextFieldDefaults.contentPaddingWithoutLabel()
        val fieldWidth = with(density) { (percentWidth + unitWidth + valueWidth).toDp() } +
            contentPadding.calculateLeftPadding(direction) + contentPadding.calculateRightPadding(direction) +
            FIELD_GAP * FIELD_HORIZONTAL_GAPS + CURSOR_CLEARANCE
        maxOf(FIELD_MIN_WIDTH, fieldWidth)
    }
}

@Composable
private fun VoltageField(
    index: Int,
    text: String,
    hasError: Boolean,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onCommitField: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val percent = BatteryOcvGrid.percentAtIndex(index)
    val percentLabel = "$percent%"
    val a11yLabel = formattedStringResource(
        Res.string.settings_battery_curve_accessibility_voltage_label,
        percent,
    )
    val a11yValue = formattedStringResource(
        Res.string.settings_battery_curve_accessibility_voltage_value,
        text.toIntOrNull() ?: 0,
    )
    val a11yHint = stringResource(Res.string.settings_battery_curve_accessibility_voltage_hint)
    val mvUnit = stringResource(Res.string.settings_battery_curve_m_v)
    var focused by remember { mutableStateOf(false) }
    var textWhenFocused by remember { mutableStateOf<String?>(null) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val intoView = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val imeBottom = if (focused) WindowInsets.ime.getBottom(density) else 0
    LaunchedEffect(focused, imeBottom, density, fieldSize) {
        if (focused && fieldSize != IntSize.Zero) {
            withFrameNanos { }
            intoView.bringIntoView()
        }
    }

    Row(
        modifier = modifier.bringIntoViewRequester(intoView).onSizeChanged { fieldSize = it },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
    ) {
        Text(
            text = percentLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppOutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = enabled,
            singleLine = true,
            isError = hasError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        focused = true
                        textWhenFocused = text
                    } else if (focused) {
                        focused = false
                        if (textWhenFocused != text) {
                            onCommitField()
                        }
                        textWhenFocused = null
                    }
                }
                .semantics {
                    contentDescription = "$a11yLabel. $a11yValue. $a11yHint"
                },
        )
        Text(
            text = mvUnit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
