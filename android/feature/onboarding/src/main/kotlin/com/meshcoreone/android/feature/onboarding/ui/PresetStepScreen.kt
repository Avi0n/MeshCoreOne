// PortedFrom: MC1/Views/Onboarding/PresetStepView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOption
import com.meshcoreone.android.feature.onboarding.PresetStepState
import java.util.Locale

data class PresetStepActions(
    val onSelect: (String) -> Unit,
    val onApply: () -> Unit,
    val onDismissError: () -> Unit,
    val onRetry: () -> Unit,
    val onDismissRetry: () -> Unit,
)

/** Frequency shown with a fixed three-decimal, locale-independent format ("910.525 MHz"). */
fun formatPresetFrequency(mhz: Double): String = String.format(Locale.ROOT, "%.3f MHz", mhz)

@Composable
fun PresetStepScreen(
    state: PresetStepState,
    regionName: String?,
    presets: List<OnboardingPresetOption>,
    canApply: Boolean,
    actions: PresetStepActions,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.commitTick) { if (state.commitTick > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    val resources = LocalContext.current.resources
    val selected = presets.firstOrNull { it.id == state.selectedId }
    OnboardingColumn(modifier) {
        val subtitle = regionName?.let { O.presetSubtitleRecommended(resources, it) } ?: stringResource(O.presetSubtitleLocale)
        OnboardingHeader(stringResource(O.presetTitle), subtitle)
        Text(stringResource(S.radioRegulationsFooter), Modifier.padding(top = OnboardingMetrics.titleStackSpacing),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = OnboardingMetrics.cardSpacing),
            verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.mediumSpacing)) {
            presets.forEach { preset -> PresetRow(preset, preset.id == state.selectedId) { actions.onSelect(preset.id) } }
            if (presets.size > 1) {
                Text(stringResource(O.presetDiscordHelp), Modifier.fillMaxWidth().padding(top = OnboardingMetrics.mediumSpacing),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        OnboardingPrimaryButton(
            selected?.let { O.presetUse(resources, it.name) } ?: stringResource(O.presetContinue), actions.onApply,
            Modifier.padding(bottom = OnboardingMetrics.cardSpacing),
            enabled = !state.isApplying && selected != null && canApply, loading = state.isApplying,
        )
    }
    state.error?.let {
        AlertDialog(
            onDismissRequest = actions.onDismissError, title = { Text(stringResource(S.alertErrorTitle)) }, text = { Text(uiString(it)) },
            confirmButton = { TextButton(actions.onDismissError) { Text(stringResource(L.commonOk)) } },
        )
    }
    state.retryAlert?.let { alert ->
        AlertDialog(
            onDismissRequest = actions.onDismissRetry,
            title = { Text(stringResource(if (alert.isMaxRetriesExceeded) S.alertRetryUnableToSave else S.alertRetryConnectionError)) },
            text = {
                Text(
                    if (alert.isMaxRetriesExceeded) stringResource(S.alertRetryEnsureConnected)
                    else uiString(alert.message),
                )
            },
            confirmButton = {
                if (alert.isMaxRetriesExceeded) TextButton(actions.onDismissRetry) { Text(stringResource(L.commonOk)) }
                else TextButton(actions.onRetry) { Text(stringResource(S.alertRetryRetry)) }
            },
            dismissButton = if (alert.isMaxRetriesExceeded) null else ({ TextButton(actions.onDismissRetry) { Text(stringResource(L.commonCancel)) } }),
        )
    }
}

@Composable
private fun PresetRow(preset: OnboardingPresetOption, isSelected: Boolean, onClick: () -> Unit) {
    val hint = stringResource(O.presetRowAccessibilityHint)
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = OnboardingMetrics.minHitTarget)
                .clickable(role = Role.RadioButton, onClick = onClick)
                .semantics(mergeDescendants = true) { selected = isSelected; onClick(hint) { onClick(); true } }
                .padding(OnboardingMetrics.cardSpacing),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.compactSpacing)) {
                Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                Text(formatPresetFrequency(preset.frequencyMHz), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSelected) Icon(MeshSymbol.CHECK.vector, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
