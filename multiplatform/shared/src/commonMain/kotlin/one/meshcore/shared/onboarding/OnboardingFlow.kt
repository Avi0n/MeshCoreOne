package one.meshcore.shared.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_ble_scan_allow
import one.meshcore.shared.resources.android_onboarding_permissions_bluetooth_description
import one.meshcore.shared.resources.android_onboarding_permissions_bluetooth_title
import one.meshcore.shared.resources.android_onboarding_permissions_subtitle
import one.meshcore.shared.resources.android_onboarding_permissions_title
import one.meshcore.shared.resources.onboarding_permissions_continue
import one.meshcore.shared.resources.onboarding_permissions_location_description
import one.meshcore.shared.resources.onboarding_permissions_location_title
import one.meshcore.shared.resources.onboarding_permissions_notifications_description
import one.meshcore.shared.resources.onboarding_permissions_notifications_title
import one.meshcore.shared.resources.onboarding_permissions_open_settings
import one.meshcore.shared.resources.onboarding_permissions_optional
import one.meshcore.shared.resources.onboarding_permissions_request
import one.meshcore.shared.resources.onboarding_welcome_get_started
import one.meshcore.shared.resources.onboarding_welcome_subtitle
import one.meshcore.shared.resources.onboarding_welcome_title
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.PermissionUiState
import one.meshcore.shared.ui.ShellDependencies
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.icons.NavigateBackIconButton
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The onboarding flow, ported from the iOS `OnboardingView` `NavigationStack`. The visible screen is the
 * top of [OnboardingViewModel.path] (empty = the Welcome root); each step's primary CTA advances the
 * stack. A resume path is pre-seeded by the graph so a returning user lands past the steps they finished,
 * mirroring the iOS resume that pushes `[permissions, pair, preset]` above Welcome.
 *
 * Steps that need the live connection (Pair, WiFi) live in `OnboardingPairStep`; this file hosts the
 * screens that only touch permissions and persisted preferences.
 */
@Composable
fun OnboardingFlow(deps: ShellDependencies, modifier: Modifier = Modifier) {
    val vm = deps.onboarding
    val path by vm.path.collectAsStateWithLifecycle()
    val step = path.lastOrNull()
    val canGoBack = path.isNotEmpty()
    // System back mirrors the scaffold back button; disabled on Welcome so the activity can finish.
    val onboardingBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = onboardingBackState,
        isBackEnabled = canGoBack,
        onBackCompleted = { vm.back() },
    )

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboardingStep",
        ) { current ->
            when (current) {
                null, OnboardingStep.WELCOME -> WelcomeStep(vm)
                OnboardingStep.PERMISSIONS -> PermissionsStep(deps)
                OnboardingStep.PAIR -> PairStep(deps)
                OnboardingStep.REGION -> RegionStep(deps)
                OnboardingStep.PRESET -> PresetStep(deps)
            }
        }
    }
}

/** A step scaffold with an optional back affordance, a scrollable body, and a pinned primary action. */
@Composable
internal fun OnboardingScaffold(onBack: (() -> Unit)?, primary: @Composable () -> Unit, body: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ONBOARDING_CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(CONTENT_PADDING),
        ) {
            if (onBack != null) {
                NavigateBackIconButton(onClick = onBack)
            } else {
                Spacer(Modifier.size(HEADER_TOP_PADDING))
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CARD_SPACING),
            ) {
                body()
            }
            Spacer(Modifier.size(MEDIUM_SPACING))
            primary()
        }
    }
}

@Composable
private fun WelcomeStep(vm: OnboardingViewModel) {
    OnboardingScaffold(
        onBack = null,
        primary = {
            Button(onClick = { vm.start() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.onboarding_welcome_get_started))
            }
        },
    ) {
        Spacer(Modifier.size(LARGE_SPACING))
        MeshAnimationView()
        Spacer(Modifier.size(LARGE_SPACING))
        StepTitle(stringResource(Res.string.onboarding_welcome_title))
        StepSubtitle(stringResource(Res.string.onboarding_welcome_subtitle))
    }
}

@Composable
private fun PermissionsStep(deps: ShellDependencies) {
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val state by deps.permissions.state.collectAsStateWithLifecycle()

    // The iOS `permissionGrantTrigger`: a success touch each time a permission newly flips to granted.
    var grantedCount by remember { mutableStateOf(state.granted.size) }
    LaunchedEffect(state.granted.size) {
        if (state.granted.size > grantedCount) haptics.success()
        grantedCount = state.granted.size
    }

    OnboardingScaffold(
        onBack = { deps.onboarding.back() },
        primary = {
            Button(onClick = { scope.launch { deps.onboarding.advance() } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.onboarding_permissions_continue))
            }
        },
    ) {
        // Android shows three permission cards, not the iOS two, so the "a couple" iOS title
        // undercounts; use the Android-specific title rather than the iOS-mirrored key.
        StepTitle(stringResource(Res.string.android_onboarding_permissions_title))
        // Android shows three cards, not the iOS two, and Bluetooth is required to connect, so the
        // subtitle uses the Android-specific copy rather than the iOS-mirrored "Both optional." line.
        StepSubtitle(stringResource(Res.string.android_onboarding_permissions_subtitle))
        PermissionCard(
            state = state,
            permission = OnboardingPermission.NOTIFICATIONS,
            title = Res.string.onboarding_permissions_notifications_title,
            description = Res.string.onboarding_permissions_notifications_description,
            onRequest = { deps.permissions.request(OnboardingPermission.NOTIFICATIONS) },
            onOpenSettings = deps.openAppSettings,
        )
        PermissionCard(
            state = state,
            permission = OnboardingPermission.LOCATION,
            title = Res.string.onboarding_permissions_location_title,
            description = Res.string.onboarding_permissions_location_description,
            onRequest = { deps.permissions.request(OnboardingPermission.LOCATION) },
            onOpenSettings = deps.openAppSettings,
        )
        // Android-new: iOS surfaces BLE permission via AccessorySetupKit at pair time (no onboarding card).
        PermissionCard(
            state = state,
            permission = OnboardingPermission.BLUETOOTH,
            title = Res.string.android_onboarding_permissions_bluetooth_title,
            description = Res.string.android_onboarding_permissions_bluetooth_description,
            onRequest = { deps.permissions.request(OnboardingPermission.BLUETOOTH) },
            onOpenSettings = deps.openAppSettings,
        )
    }
}

@Composable
private fun PermissionCard(
    state: PermissionUiState,
    permission: OnboardingPermission,
    title: StringResource,
    description: StringResource,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val granted = state.isGranted(permission)
    // Asked and refused: the system prompt will silently no-op, so route to system Settings instead
    // (the iOS PermissionCard denied state).
    val denied = !granted && permission in state.determined
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CONTENT_PADDING),
            verticalArrangement = Arrangement.spacedBy(MEDIUM_SPACING),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MEDIUM_SPACING),
            ) {
                Icon(
                    imageVector = permissionLeadingIcon(permission),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(COMPACT_SPACING)) {
                    Text(
                        text = stringResource(title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (onboardingPermissionIsOptional(permission)) {
                        Text(
                            text = stringResource(Res.string.onboarding_permissions_optional),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (granted) {
                    Icon(
                        imageVector = AppIcons.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!granted) {
                if (denied) {
                    Button(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MIN_HIT_TARGET),
                    ) {
                        Text(stringResource(Res.string.onboarding_permissions_open_settings))
                    }
                } else {
                    FilledTonalButton(
                        onClick = onRequest,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MIN_HIT_TARGET),
                    ) {
                        Text(
                            stringResource(
                                if (permission == OnboardingPermission.BLUETOOTH) {
                                    Res.string.android_ble_scan_allow
                                } else {
                                    Res.string.onboarding_permissions_request
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun permissionLeadingIcon(permission: OnboardingPermission) = when (permission) {
    OnboardingPermission.NOTIFICATIONS -> AppIcons.Notifications
    OnboardingPermission.LOCATION -> AppIcons.LocationOn
    OnboardingPermission.BLUETOOTH -> AppIcons.Bluetooth
    OnboardingPermission.CAMERA -> AppIcons.QrCodeScanner
}

@Composable
internal fun StepTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun StepSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

// OnboardingMetrics (dp), from the iOS OnboardingMetrics; the 48dp hit target replaces iOS's 44pt.
internal val HERO_SIZE = 130.dp
internal val HERO_ICON_SIZE = 64.dp
internal val CONTENT_PADDING = 20.dp

/** Caps the wizard on tablets so Get Started does not span the window. */
internal val ONBOARDING_CONTENT_MAX_WIDTH = 840.dp
internal val CARD_SPACING = 16.dp
internal val LARGE_SPACING = 24.dp
internal val MEDIUM_SPACING = 12.dp
internal val COMPACT_SPACING = 4.dp
internal val HEADER_TOP_PADDING = 40.dp
internal val MIN_HIT_TARGET = 48.dp

/** Notifications and location can be skipped; Bluetooth is required to pair on Android. */
internal fun onboardingPermissionIsOptional(permission: OnboardingPermission): Boolean =
    permission != OnboardingPermission.BLUETOOTH
