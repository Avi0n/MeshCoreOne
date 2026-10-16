package one.meshcore.shared.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.map.regionDisplayName
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.onboarding_region_choose_another
import one.meshcore.shared.resources.onboarding_region_continue
import one.meshcore.shared.resources.onboarding_region_detected_source
import one.meshcore.shared.resources.onboarding_region_detected_tag
import one.meshcore.shared.resources.onboarding_region_resolving
import one.meshcore.shared.resources.onboarding_region_subtitle
import one.meshcore.shared.resources.onboarding_region_title
import one.meshcore.shared.resources.onboarding_region_use_my_location
import one.meshcore.shared.resources.onboarding_region_use_my_location_failure
import one.meshcore.shared.resources.onboarding_region_use_this_region
import one.meshcore.shared.settings.ui.RegionPickerRows
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.ShellDependencies
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * The Region step, ported from the iOS `RegionStepView`: resolve the region from the device location,
 * offer the detection for one-tap confirm, and fall silently to the manual picker on any failure
 * (denied permission, timeout, no geocoder result) or when the user chooses another region.
 */
@Composable
internal fun RegionStep(deps: ShellDependencies) {
    var resolved by remember { mutableStateOf<RegionSelection?>(null) }
    var isResolving by remember { mutableStateOf(true) }
    var showManualPicker by remember { mutableStateOf(false) }
    var resolveAttempt by remember { mutableStateOf(0) }
    var pendingUserRetry by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionState by deps.permissions.state.collectAsStateWithLifecycle()
    val locationGranted = permissionState.isGranted(OnboardingPermission.LOCATION)
    val resolveRegion = deps.resolveRegion
    val useMyLocationFailure = stringResource(Res.string.onboarding_region_use_my_location_failure)

    // Runs on entry (attempt 0) and every re-resolve (manual "Use my location" or a permission flip),
    // mirroring the iOS RegionStepView `.task(id: resolveAttempt)`.
    LaunchedEffect(resolveAttempt, resolveRegion) {
        if (!locationGranted || showManualPicker) {
            isResolving = false
            return@LaunchedEffect
        }
        isResolving = true
        resolved = resolveRegion()
        isResolving = false
        val wasUserRetry = pendingUserRetry
        pendingUserRetry = false
        if (resolved == null) {
            if (wasUserRetry) errorMessage = useMyLocationFailure
            showManualPicker = true
        }
    }

    // Permission flipped from Settings while on screen: re-fire the resolver (iOS onChange(of: locationGranted)).
    var lastLocationGranted by remember { mutableStateOf(locationGranted) }
    LaunchedEffect(locationGranted) {
        if (locationGranted != lastLocationGranted) {
            lastLocationGranted = locationGranted
            resolveAttempt += 1
        }
    }

    // Re-resolve from the manual picker, clearing the prior detection so the spinner shows while it runs.
    val onUseMyLocation = {
        resolved = null
        showManualPicker = false
        isResolving = true
        errorMessage = null
        pendingUserRetry = true
        resolveAttempt += 1
    }

    val detected = resolved
    when {
        showManualPicker || !locationGranted -> RegionManualState(
            vm = deps.onboarding,
            onUseMyLocation = onUseMyLocation.takeIf { locationGranted },
        )
        detected != null -> RegionDetectedState(
            vm = deps.onboarding,
            region = detected,
            onChooseAnother = { showManualPicker = true },
        )
        isResolving -> RegionResolvingState(deps.onboarding)
        else -> RegionManualState(
            vm = deps.onboarding,
            onUseMyLocation = onUseMyLocation.takeIf { locationGranted },
        )
    }
    errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = { errorMessage = null })
    }
}

@Composable
private fun RegionResolvingState(vm: OnboardingViewModel) {
    OnboardingScaffold(onBack = { vm.back() }, primary = {}) {
        StepTitle(stringResource(Res.string.onboarding_region_title))
        StepSubtitle(stringResource(Res.string.onboarding_region_subtitle))
        Spacer(Modifier.size(LARGE_SPACING))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MEDIUM_SPACING),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(Res.string.onboarding_region_resolving),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RegionDetectedState(vm: OnboardingViewModel, region: RegionSelection, onChooseAnother: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    OnboardingScaffold(
        onBack = { vm.back() },
        primary = {
            Button(
                onClick = {
                    scope.launch {
                        vm.commitRegion(region)
                        haptics.success()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.onboarding_region_use_this_region))
            }
        },
    ) {
        StepTitle(stringResource(Res.string.onboarding_region_title))
        StepSubtitle(stringResource(Res.string.onboarding_region_subtitle))
        Spacer(Modifier.size(LARGE_SPACING))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(CONTENT_PADDING),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(COMPACT_SPACING),
            ) {
                Text(
                    text = stringResource(Res.string.onboarding_region_detected_tag),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = regionDisplayName(region),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.onboarding_region_detected_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = onChooseAnother) {
                Text(stringResource(Res.string.onboarding_region_choose_another))
            }
        }
    }
}

@Composable
private fun RegionManualState(vm: OnboardingViewModel, onUseMyLocation: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    var selection by remember { mutableStateOf<RegionSelection?>(null) }

    OnboardingScaffold(
        onBack = { vm.back() },
        primary = {
            Button(
                onClick = {
                    val region = selection ?: return@Button
                    scope.launch {
                        vm.commitRegion(region)
                        haptics.success()
                    }
                },
                enabled = selection != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.onboarding_region_continue))
            }
        },
    ) {
        StepTitle(stringResource(Res.string.onboarding_region_title))
        StepSubtitle(stringResource(Res.string.onboarding_region_subtitle))
        // Retry location detection from within the manual picker (iOS "Use my location" button).
        if (onUseMyLocation != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                FilledTonalButton(onClick = onUseMyLocation) {
                    Text(stringResource(Res.string.onboarding_region_use_my_location))
                }
            }
        }
        PreferenceHubSection(applyHorizontalPadding = false) {
            RegionPickerRows(
                selection = selection,
                onSelectionChange = { selection = it },
            )
        }
    }
}
