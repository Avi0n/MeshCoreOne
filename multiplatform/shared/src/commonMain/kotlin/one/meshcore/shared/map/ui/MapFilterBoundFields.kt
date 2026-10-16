package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.MapDuration
import one.meshcore.shared.map.MapDurationUnit
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.map_filters_last_heard_unit_label
import one.meshcore.shared.resources.map_filters_no_limit
import one.meshcore.shared.ui.AppOutlinedTextField
import org.jetbrains.compose.resources.stringResource

private val BOUND_FIELD_GAP = 8.dp

@Composable
internal fun MapIntegerBoundField(
    label: String,
    value: Int?,
    range: IntRange,
    onCommit: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    val commit by rememberUpdatedState(onCommit)
    val currentText by rememberUpdatedState(text)
    val focusManager = LocalFocusManager.current
    val commitText = {
        val parsed = text.toIntOrNull()?.coerceIn(range)
        commit(parsed)
        text = parsed?.toString().orEmpty()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (focused) commit(currentText.toIntOrNull()?.coerceIn(range))
        }
    }
    AppOutlinedTextField(
        value = text,
        onValueChange = { next -> text = mapBoundDigits(next, range) },
        label = { Text(label) },
        placeholder = { Text(stringResource(Res.string.map_filters_no_limit)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            commitText()
            focusManager.clearFocus()
        }),
        singleLine = true,
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused) commitText()
            focused = it.isFocused
        },
    )
}

internal fun mapBoundDigits(text: String, range: IntRange): String =
    text.filter { it in '0'..'9' }.take(range.last.toString().length)

@Composable
internal fun MapDurationBoundField(label: String, value: MapDuration?, onCommit: (MapDuration?) -> Unit) {
    var unit by remember { mutableStateOf(value?.unit ?: MapDurationUnit.HOURS) }
    LaunchedEffect(value?.unit) { value?.unit?.let { unit = it } }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BOUND_FIELD_GAP),
    ) {
        MapIntegerBoundField(
            label = label,
            value = value?.value,
            range = MapDuration.VALUE_RANGE,
            onCommit = { onCommit(it?.let { number -> MapDuration(number, unit) }) },
            modifier = Modifier.weight(1f),
        )
        MapDurationUnitPicker(unit) { next ->
            unit = next
            value?.let { onCommit(MapDuration(it.value, next)) }
        }
    }
}

@Composable
private fun MapDurationUnitPicker(unit: MapDurationUnit, onChange: (MapDurationUnit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val unitLabel = stringResource(Res.string.map_filters_last_heard_unit_label)
    val selectedLabel = unit.label()
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "$unitLabel: $selectedLabel" },
        ) { Text(selectedLabel) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MapDurationUnit.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
}
