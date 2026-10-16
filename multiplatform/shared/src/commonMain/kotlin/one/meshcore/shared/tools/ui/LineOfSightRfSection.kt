package one.meshcore.shared.tools.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_save
import one.meshcore.shared.resources.tools_line_of_sight_add_repeater
import one.meshcore.shared.resources.tools_line_of_sight_analyze
import one.meshcore.shared.resources.tools_line_of_sight_analyzing
import one.meshcore.shared.resources.tools_line_of_sight_drag_to_adjust
import one.meshcore.shared.resources.tools_line_of_sight_frequency
import one.meshcore.shared.resources.tools_line_of_sight_mhz
import one.meshcore.shared.resources.tools_line_of_sight_refraction
import one.meshcore.shared.resources.tools_line_of_sight_refraction_ducting
import one.meshcore.shared.resources.tools_line_of_sight_refraction_none
import one.meshcore.shared.resources.tools_line_of_sight_refraction_standard
import one.meshcore.shared.resources.tools_line_of_sight_repeater
import one.meshcore.shared.resources.tools_line_of_sight_repeater_location
import one.meshcore.shared.resources.tools_line_of_sight_rf_settings
import one.meshcore.shared.settings.ui.RadioOptionPicker
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.LosNumericDraft
import one.meshcore.shared.tools.LosPointId
import one.meshcore.shared.tools.RefractionPreset
import one.meshcore.shared.tools.addRepeater
import one.meshcore.shared.tools.clearRepeater
import one.meshcore.shared.tools.parseLosNumber
import one.meshcore.shared.tools.setFrequencyMHz
import one.meshcore.shared.tools.setRefractionK
import one.meshcore.shared.tools.updateRepeaterHeight
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AnalyzeLosButton(isAnalyzing: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !isAnalyzing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isAnalyzing) {
            CircularProgressIndicator(
                modifier = Modifier.size(LOS_ANALYZE_SPINNER_DP.dp),
                strokeWidth = LOS_ANALYZE_SPINNER_STROKE_DP.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(LOS_ANALYZE_ICON_GAP.dp))
            Text(stringResource(Res.string.tools_line_of_sight_analyzing))
        } else {
            Icon(
                imageVector = LOS_ANALYZE_ICON,
                contentDescription = null,
                modifier = Modifier.size(LOS_ANALYZE_ICON_DP.dp),
            )
            Spacer(Modifier.size(LOS_ANALYZE_ICON_GAP.dp))
            Text(stringResource(Res.string.tools_line_of_sight_analyze))
        }
    }
}

@Composable
internal fun RfSettingsSection(viewModel: LineOfSightViewModel) {
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    check(revision >= 0)

    PreferenceHubSection(applyHorizontalPadding = false) {
        val rfTitle = stringResource(Res.string.tools_line_of_sight_rf_settings)
        ListItem(
            headlineContent = { Text(rfTitle) },
            leadingContent = {
                Icon(
                    imageVector = LOS_RF_SETTINGS_ICON,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(LOS_SECTION_ICON_DP.dp),
                )
            },
            modifier = Modifier.semantics { contentDescription = rfTitle },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Column(
            Modifier.padding(PreferenceHubCardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var frequency by remember { mutableStateOf(LosNumericDraft.forValue(viewModel.frequencyMHz)) }
            var frequencyFocused by remember { mutableStateOf(false) }
            val focusManager = LocalFocusManager.current
            val commitFrequency = {
                viewModel.setFrequencyMHz(frequency.committed)
                focusManager.clearFocus()
            }
            LaunchedEffect(viewModel.frequencyMHz) { frequency = frequency.synced(viewModel.frequencyMHz) }
            AppOutlinedTextField(
                value = frequency.text,
                onValueChange = { typed ->
                    val next = frequency.typed(typed) { it > 0 }
                    parseLosNumber(typed)?.takeIf { it > 0 }?.let(viewModel::setFrequencyMHz)
                    frequency = next
                },
                label = { Text(stringResource(Res.string.tools_line_of_sight_frequency)) },
                leadingIcon = {
                    Icon(
                        imageVector = LOS_FREQUENCY_ICON,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                suffix = {
                    Text(stringResource(Res.string.tools_line_of_sight_mhz))
                },
                trailingIcon = if (frequencyFocused) {
                    {
                        AppIconButton(
                            imageVector = LOS_FREQUENCY_COMMIT_ICON,
                            contentDescription = stringResource(Res.string.localizable_common_save),
                            onClick = commitFrequency,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commitFrequency() }),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { frequencyFocused = it.isFocused },
            )
            // A fixed set, not free entry: iOS offers exactly these three, and the names are what tell an
            // operator that 1.33 is standard atmosphere. An arbitrary k silently changes whether a path reads
            // as clear.
            val selected = RefractionPreset.forKFactor(viewModel.refractionK)
            val refractionLabel = stringResource(Res.string.tools_line_of_sight_refraction)
            // Resolved here rather than inside the picker's label lambdas, which are not composable.
            val presetLabels = mapOf(
                RefractionPreset.NONE to stringResource(Res.string.tools_line_of_sight_refraction_none),
                RefractionPreset.STANDARD to stringResource(Res.string.tools_line_of_sight_refraction_standard),
                RefractionPreset.DUCTING to stringResource(Res.string.tools_line_of_sight_refraction_ducting),
            )
            RadioOptionPicker(
                label = refractionLabel,
                selectedLabel = presetLabels.getValue(selected),
                selectedAccessibilityLabel = presetLabels.getValue(selected),
                accessibilityHint = refractionLabel,
                options = RefractionPreset.entries,
                optionLabel = { presetLabels.getValue(it) },
                optionAccessibilityLabel = { presetLabels.getValue(it) },
                enabled = true,
                onSelect = { viewModel.setRefractionK(it.kFactor) },
                leadingIcon = LOS_REFRACTION_ICON,
            )
        }
    }
}

@Composable
internal fun RepeaterSection(
    viewModel: LineOfSightViewModel,
    shareText: (String) -> Unit,
    showDragHint: Boolean,
    isEditing: Boolean,
    onToggleEdit: () -> Unit,
) {
    // The view model exposes plain fields; observe its revision here so this skipped sheet
    // subtree refreshes when Add Repeater replaces the placeholder with the live point.
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    check(revision >= 0)
    val repeater = viewModel.repeaterPoint
    if (repeater == null) {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.tools_line_of_sight_add_repeater)) },
            leadingContent = {
                LosPointMarker(
                    label = TERRAIN_REPEATER_LABEL,
                    role = LOS_REPEATER_ROLE,
                    filled = true,
                    contentDescription = stringResource(Res.string.tools_line_of_sight_repeater),
                )
            },
            trailingContent = {
                Icon(
                    imageVector = LOS_ADD_REPEATER_ICON,
                    contentDescription = null,
                    tint = LOS_REPEATER_ROLE.tint(),
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = viewModel.shouldShowRepeaterPlaceholder,
                    onClick = { viewModel.addRepeater() },
                ),
        )
    } else {
        Column(Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(Res.string.tools_line_of_sight_repeater),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    ElevationReadout(
                        groundElevation = repeater.groundElevation,
                        totalHeight = repeater.groundElevation?.let { it + repeater.additionalHeight },
                    )
                },
                leadingContent = {
                    LosPointMarker(
                        label = TERRAIN_REPEATER_LABEL,
                        role = LOS_REPEATER_ROLE,
                        filled = true,
                        contentDescription = stringResource(Res.string.tools_line_of_sight_repeater),
                    )
                },
                trailingContent = {
                    PointActionButtons(
                        pointLabel = stringResource(Res.string.tools_line_of_sight_repeater_location),
                        coordinate = repeater.coordinate,
                        shareText = shareText,
                        isEditing = isEditing,
                        relocateEnabled = viewModel.relocatingPoint == null ||
                            viewModel.relocatingPoint == LosPointId.REPEATER,
                        onRelocate = {
                            viewModel.relocatingPoint =
                                if (viewModel.relocatingPoint == LosPointId.REPEATER) {
                                    null
                                } else {
                                    LosPointId.REPEATER
                                }
                        },
                        onToggleEdit = onToggleEdit,
                        onClear = { viewModel.clearRepeater() },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
            if (showDragHint && repeater.isOnPath) {
                Text(
                    text = stringResource(Res.string.tools_line_of_sight_drag_to_adjust),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (isEditing) {
                AdditionalHeightStepper(
                    meters = repeater.additionalHeight,
                    onChange = { viewModel.updateRepeaterHeight(it) },
                )
            }
        }
    }
}

/** iOS `RFSettingsSectionView` `antenna.radiowaves.left.and.right`. */
internal val LOS_RF_SETTINGS_ICON get() = AppIcons.CellTower

/** iOS `globe` on refraction and earth-curvature labels. */
internal val LOS_REFRACTION_ICON get() = AppIcons.Public

/** iOS `AddRepeaterRowView` `plus.circle.fill`. */
internal val LOS_ADD_REPEATER_ICON get() = AppIcons.AddCircle

/** iOS `AnalyzeButton` `waveform.path`. */
internal val LOS_ANALYZE_ICON get() = AppIcons.GraphicEq
private const val LOS_ANALYZE_ICON_DP = 18
private const val LOS_ANALYZE_ICON_GAP = 8
private const val LOS_ANALYZE_SPINNER_DP = 18
private const val LOS_ANALYZE_SPINNER_STROKE_DP = 2

/** iOS `FrequencyInputRow` antenna glyph. */
internal val LOS_FREQUENCY_ICON get() = AppIcons.CellTower

/** iOS `checkmark.circle.fill` while the frequency field is focused. */
internal val LOS_FREQUENCY_COMMIT_ICON get() = AppIcons.CheckCircle
