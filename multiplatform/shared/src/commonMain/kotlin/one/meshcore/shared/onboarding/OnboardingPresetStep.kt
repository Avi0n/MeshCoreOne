package one.meshcore.shared.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.map.regionDisplayName
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_ok
import one.meshcore.shared.resources.onboarding_preset_continue
import one.meshcore.shared.resources.onboarding_preset_discord_help
import one.meshcore.shared.resources.onboarding_preset_error_not_connected
import one.meshcore.shared.resources.onboarding_preset_row_accessibility_hint
import one.meshcore.shared.resources.onboarding_preset_subtitle_locale
import one.meshcore.shared.resources.onboarding_preset_subtitle_recommended
import one.meshcore.shared.resources.onboarding_preset_title
import one.meshcore.shared.resources.onboarding_preset_use
import one.meshcore.shared.resources.settings_alert_error_title
import one.meshcore.shared.resources.settings_alert_retry_fallback_message
import one.meshcore.shared.resources.settings_radio_regulations_footer
import one.meshcore.shared.settings.RadioPreset
import one.meshcore.shared.settings.RadioRegion
import one.meshcore.shared.settings.ui.RadioPresetListItem
import one.meshcore.shared.settings.ui.RetryAlert
import one.meshcore.shared.settings.ui.rememberRetryAlertState
import one.meshcore.shared.settings.ui.rememberSettingsErrorText
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.ShellDependencies
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * The Preset step, ported from the iOS `PresetStepView`. Lists presets for the selected place (or the
 * locale catalog when no place is set). Rows only change the selection; the primary "Use <preset>"
 * CTA applies the selected preset over the live session and completes onboarding on success. A
 * retryable apply failure raises the shared retry alert; a non-retryable one and a maxed retry
 * present an Error alert.
 */
@Composable
internal fun PresetStep(deps: ShellDependencies) {
    val region = deps.regionSelection()
    PresetPickerState(deps = deps, region = region)
}

@Composable
private fun PresetPickerState(deps: ShellDependencies, region: RegionSelection?) {
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    var isApplying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val retryAlert = rememberRetryAlertState()
    val presets = remember(region) { onboardingSelectablePresets(region) }
    val byRegion = remember(presets) { presets.groupBy { it.region } }
    // Rows only change the selection; the primary CTA applies it. Starts empty so the user must tap
    // a row, matching iOS `PresetStepView` (`selectedID` is nil until a card is tapped).
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selectedPreset = presets.firstOrNull { it.id == selectedId }
    val notConnectedMessage = stringResource(Res.string.onboarding_preset_error_not_connected)
    val rowHint = stringResource(Res.string.onboarding_preset_row_accessibility_hint)
    val settingsErrorText = rememberSettingsErrorText()
    val retryFallbackMessage = stringResource(Res.string.settings_alert_retry_fallback_message)
    val currentDeps by rememberUpdatedState(deps)
    val currentNotConnectedMessage by rememberUpdatedState(notConnectedMessage)
    val currentSettingsErrorText by rememberUpdatedState(settingsErrorText)
    val currentRetryFallbackMessage by rememberUpdatedState(retryFallbackMessage)

    fun applyPreset(preset: RadioPreset) {
        if (isApplying) return
        val operationDeps = currentDeps
        if (operationDeps.radioSnapshot() == null) {
            errorMessage = currentNotConnectedMessage
            return
        }
        isApplying = true
        errorMessage = null
        scope.launch {
            try {
                when (
                    val outcome = applyOnboardingPreset(
                        apply = { operationDeps.applyRadioPreset(preset) },
                        onApplied = {
                            retryAlert.reset()
                            haptics.success()
                            currentDeps.onboarding.complete()
                        },
                        errorText = { currentSettingsErrorText(it) },
                    )
                ) {
                    OnboardingPresetApply.Applied -> Unit
                    is OnboardingPresetApply.Retryable -> retryAlert.show(
                        message = outcome.message,
                        onRetry = { applyPreset(preset) },
                        onMaxRetriesExceeded = { errorMessage = currentRetryFallbackMessage },
                    )
                    is OnboardingPresetApply.Failed -> errorMessage = outcome.message
                }
            } finally {
                isApplying = false
            }
        }
    }

    PresetPickerContent(
        region = region,
        isApplying = isApplying,
        selectedPreset = selectedPreset,
        highlightedId = selectedId,
        byRegion = byRegion,
        hasMultiplePresets = presets.size > 1,
        rowHint = rowHint,
        onBack = { currentDeps.onboarding.back() },
        onApply = { applyPreset(it) },
        onSelect = { preset -> selectedId = preset.id },
    )
    RetryAlert(retryAlert)
    errorMessage?.let { message ->
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.settings_alert_error_title),
            message = message,
            confirmLabel = stringResource(Res.string.localizable_common_ok),
            onDismiss = { errorMessage = null },
        )
    }
}

@Composable
private fun PresetPickerContent(
    region: RegionSelection?,
    isApplying: Boolean,
    selectedPreset: RadioPreset?,
    highlightedId: String?,
    byRegion: Map<RadioRegion, List<RadioPreset>>,
    hasMultiplePresets: Boolean,
    rowHint: String,
    onBack: () -> Unit,
    onApply: (RadioPreset) -> Unit,
    onSelect: (RadioPreset) -> Unit,
) {
    OnboardingScaffold(
        onBack = onBack,
        primary = {
            Button(
                onClick = { selectedPreset?.let(onApply) },
                enabled = !isApplying && selectedPreset != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isApplying) {
                    FilledButtonProgress()
                }
                Text(
                    if (selectedPreset != null) {
                        formattedStringResource(Res.string.onboarding_preset_use, selectedPreset.name)
                    } else {
                        stringResource(Res.string.onboarding_preset_continue)
                    },
                )
            }
        },
    ) {
        StepTitle(stringResource(Res.string.onboarding_preset_title))
        StepSubtitle(
            if (region != null) {
                formattedStringResource(
                    Res.string.onboarding_preset_subtitle_recommended,
                    regionDisplayName(region),
                )
            } else {
                stringResource(Res.string.onboarding_preset_subtitle_locale)
            },
        )
        Text(
            text = stringResource(Res.string.settings_radio_regulations_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = MEDIUM_SPACING),
        )
        PresetRegionList(
            byRegion = byRegion,
            highlightedId = highlightedId,
            enabled = !isApplying,
            rowHint = rowHint,
            onSelect = onSelect,
        )
        if (hasMultiplePresets) {
            PresetDiscordHelp(
                source = stringResource(Res.string.onboarding_preset_discord_help),
                modifier = Modifier.fillMaxWidth().padding(top = MEDIUM_SPACING),
            )
        }
    }
}

@Composable
private fun PresetDiscordHelp(source: String, modifier: Modifier = Modifier) {
    val parsed = remember(source) { markdownLinkDisplay(source) }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(parsed, linkColor) {
        buildAnnotatedString {
            append(parsed.text)
            val url = parsed.url
            if (url != null && parsed.urlEnd > parsed.urlStart) {
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    parsed.urlStart,
                    parsed.urlEnd,
                )
                addLink(LinkAnnotation.Url(url), parsed.urlStart, parsed.urlEnd)
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** The preset catalog grouped by [RadioRegion], each group headed by its display name. */
@Composable
private fun PresetRegionList(
    byRegion: Map<RadioRegion, List<RadioPreset>>,
    highlightedId: String?,
    enabled: Boolean,
    rowHint: String,
    onSelect: (RadioPreset) -> Unit,
) {
    for (regionEntry in RadioRegion.entries) {
        val regionPresets = byRegion[regionEntry].orEmpty()
        if (regionPresets.isEmpty()) continue
        PreferenceHubSection(
            modifier = Modifier.padding(top = COMPACT_SPACING),
            title = regionEntry.displayName,
            applyHorizontalPadding = false,
        ) {
            for (preset in regionPresets) {
                OnboardingPresetRow(
                    preset = preset,
                    selected = preset.id == highlightedId,
                    enabled = enabled,
                    onClickLabel = rowHint,
                    onSelect = { onSelect(preset) },
                )
            }
        }
    }
}

@Composable
private fun OnboardingPresetRow(
    preset: RadioPreset,
    selected: Boolean,
    enabled: Boolean,
    onClickLabel: String,
    onSelect: () -> Unit,
) {
    RadioPresetListItem(
        preset = preset,
        selected = selected,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClickLabel = onClickLabel, onClick = onSelect),
    )
}
