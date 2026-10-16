package one.meshcore.shared.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventTransitionState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.billing.SideloadDistribution
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.ui.LocalInteractivePopActive
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.rememberTimestampFormatter
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.navigation.handleSystemBack
import one.meshcore.shared.navigation.navigateToSettings
import one.meshcore.shared.navigation.selectSetting
import one.meshcore.shared.navigation.selectTab
import one.meshcore.shared.onboarding.OnboardingFlow
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_accessibility_connection_device_connection_lost
import one.meshcore.shared.resources.localizable_accessibility_connection_device_reconnected
import one.meshcore.shared.resources.settings_appearance_accessibility_theme_reverted
import one.meshcore.shared.settings.settingsDestinationReadsRadio
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.theme.AppearancePreferencesStore
import one.meshcore.shared.theme.LocalAppTheme
import one.meshcore.shared.theme.LocalUseDarkTheme
import one.meshcore.shared.theme.SystemBarAppearance
import one.meshcore.shared.theme.ThemeAccess
import one.meshcore.shared.theme.ThemeRegistry
import one.meshcore.shared.theme.platformDynamicColorScheme
import one.meshcore.shared.theme.resolveAppIsDark
import one.meshcore.shared.whatsnew.shouldPresentWhatsNew
import one.meshcore.shared.whatsnew.ui.WhatsNewSheet
import org.jetbrains.compose.resources.stringResource

/**
 * The shared root: it decides between the onboarding flow and the tab/list-detail shell, and overlays the
 * connection chrome that both shells share — the status pill (top-center) and the battery + BLE status
 * menu (top-end, only on the tabs that host the indicator). The iOS `ContentView` makes the same
 * decision (onboarding vs `MainTabView`/`MainSidebarView`) and hosts the pill overlay above both.
 *
 * The onboarding→shell switch is animated, matching the iOS `.animation(.default, value:)` on the same
 * boundary.
 */
@Composable
fun AppRoot(deps: ShellDependencies, modifier: Modifier = Modifier) {
    val schemePref = deps.appearancePreferences?.colorScheme?.collectAsStateWithLifecycle()?.value
        ?: AppColorSchemePreference.SYSTEM
    val themeId = rememberAccessibleThemeId(
        appearance = deps.appearancePreferences,
        entitlements = deps.entitlementStore,
    )
    val appTheme = ThemeRegistry.byId(themeId)
    val systemDark = isSystemInDarkTheme()
    val useDark = resolveAppIsDark(schemePref, systemDark, appTheme.forceDark)
    val mapColorScheme = deps.mapDisplayPreferences?.mapColorScheme?.collectAsStateWithLifecycle()?.value
        ?: AppColorSchemePreference.SYSTEM
    val networkOffline = shellNetworkOffline(deps)
    val highContrast = rememberHighContrast()
    // Default theme: Android 12+ dynamic color when available; branded themes keep registry palettes.
    val dynamicScheme = if (themeId == AppTheme.DEFAULT_ID) {
        platformDynamicColorScheme(useDark)
    } else {
        null
    }
    val colorScheme = dynamicScheme ?: appTheme.materialColorScheme(useDark)
    // System-bar icons follow the resolved in-app scheme, not the system theme.
    SystemBarAppearance(useDark)
    CompositionLocalProvider(
        LocalTimestampFormatter provides rememberTimestampFormatter(),
        LocalAppTheme provides appTheme,
        LocalUseDarkTheme provides useDark,
        LocalMapColorSchemePreference provides mapColorScheme,
        LocalMapOffline provides networkOffline,
        LocalHighContrast provides highContrast,
        LocalShareImageBytes provides (deps.shareImageBytes ?: {}),
    ) {
        // Explicit M3 type + shape scales so defaults are never left implicit across hosts.
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            shapes = Shapes(),
        ) {
            Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val onboardingDone by deps.onboarding.completed.collectAsStateWithLifecycle()
                val showOnboarding = !(deps.initialOnboardingComplete || onboardingDone)

                Crossfade(targetState = showOnboarding, label = "root") { onboarding ->
                    if (onboarding) {
                        OnboardingFlow(deps)
                    } else {
                        MainShellWithChrome(deps)
                    }
                }
                // Hosted above the crossfade, not inside the shell: a pairing failure during first-run
                // pairing is exactly when the guided recovery matters, and the shell is not on screen yet.
                ConnectionAlertDialog(deps)

                val whatsNewStore = deps.whatsNewStore
                val uriHandler = LocalUriHandler.current
                if (!showOnboarding && whatsNewStore != null) {
                    val pending by whatsNewStore.pendingRelease.collectAsStateWithLifecycle()
                    val alert by deps.connectionAlert.collectAsStateWithLifecycle()
                    pending
                        ?.takeIf { shouldPresentWhatsNew(it, connectionAlertPresent = alert != null) }
                        ?.let { release ->
                            WhatsNewSheet(
                                release = release,
                                onDismiss = { whatsNewStore.markShown() },
                                onSupport = {
                                    if (SideloadDistribution.BILLING_ENABLED) {
                                        deps.coordinator.navigateToSettings(SettingsDestination.SUPPORT)
                                    } else {
                                        uriHandler.openUri(SideloadDistribution.SPONSORS_URL)
                                    }
                                    whatsNewStore.markShown()
                                },
                            )
                        }
                }
            }
        }
    }
}

/**
 * Speaks "Device connection lost" and "Device reconnected" as the link goes and comes back, mirroring the
 * two iOS `ConnectionUIState` announcements. Neither can be a live region: they fire whether or not the
 * pill is allowed to show, and the pill's own appearance is both delayed and conditional, so nothing on
 * screen changes its text at the moment the link changes.
 */
@Composable
private fun ConnectionStateAnnouncer(connectionState: DeviceConnectionState) {
    val announcer = rememberAccessibilityAnnouncer()
    val lost = stringResource(Res.string.localizable_accessibility_connection_device_connection_lost)
    val reconnected = stringResource(Res.string.localizable_accessibility_connection_device_reconnected)
    val previous = remember { mutableStateOf(connectionState) }
    LaunchedEffect(connectionState) {
        val was = previous.value
        previous.value = connectionState
        when {
            isConnectionLost(was, connectionState) -> announcer.announce(lost)
            isDeviceReconnected(was, connectionState) -> announcer.announce(reconnected)
        }
    }
}

/**
 * Applies the selected theme when still entitled; on refund/revoke persists and returns the free default.
 */
@Composable
private fun rememberAccessibleThemeId(
    appearance: AppearancePreferencesStore?,
    entitlements: EntitlementStore?,
): String {
    val selectedThemeId = appearance?.selectedThemeId?.collectAsStateWithLifecycle()?.value ?: AppTheme.DEFAULT_ID
    val ownedProducts = entitlements?.ownedProducts?.collectAsStateWithLifecycle()?.value.orEmpty()
    // Collected, not just read: a query that answers "nothing owned" leaves the owned set unchanged,
    // so this flag is the only signal that the revert may now run.
    val entitlementsAuthoritative = entitlements?.isAuthoritative?.collectAsStateWithLifecycle()?.value ?: true
    val themeId = ThemeAccess.resolveAccessibleThemeId(selectedThemeId, entitlements)
    val announcer = rememberAccessibilityAnnouncer()
    val revertedMessage = stringResource(Res.string.settings_appearance_accessibility_theme_reverted)
    LaunchedEffect(selectedThemeId, ownedProducts, entitlementsAuthoritative) {
        if (ThemeAccess.persistAccessibleThemeId(selectedThemeId, entitlements, appearance)) {
            announcer.announce(revertedMessage)
        }
    }
    return themeId
}

@Composable
private fun MainShellWithChrome(deps: ShellDependencies) {
    val scope = rememberCoroutineScope()
    val networkOffline = LocalMapOffline.current
    val unread by deps.unreadCount.collectAsStateWithLifecycle()
    val pill by deps.statusPill.collectAsStateWithLifecycle()
    val connectionState by deps.connectionState.collectAsStateWithLifecycle()
    val battery by deps.battery.collectAsStateWithLifecycle()
    val batteryOcvArray by deps.batteryOcvArray.collectAsStateWithLifecycle()
    ConnectionStateAnnouncer(connectionState)
    val nodeName by deps.localNodeName.collectAsStateWithLifecycle()
    val clientRepeat by deps.radioClientRepeat.collectAsStateWithLifecycle()
    val selectedTab by deps.coordinator.selectedTab.collectAsStateWithLifecycle()
    // Observe nav surfaces that system-back can pop (stacks, tools, settings).
    val backStacks by deps.coordinator.backStacks.collectAsStateWithLifecycle()
    val selectedTool by deps.coordinator.selectedTool.collectAsStateWithLifecycle()
    val selectedSetting by deps.coordinator.selectedSetting.collectAsStateWithLifecycle()

    LaunchedEffect(deps.coordinator, deps.nodeConfigImportWorkspace) {
        combine(deps.coordinator.selectedTab, deps.coordinator.selectedSetting) { _, _ -> Unit }.collect {
            deps.nodeConfigImportWorkspace?.retireIfDestinationChanged()
        }
    }

    // The Settings snapshots all read a cache written at connect, so a screen that renders radio-owned
    // values re-reads the radio when it appears — as iOS does from each screen's startup task. Keyed on
    // the gate too, so a screen opened while the connect sync is still contended reads once it quietens.
    val settingsReadsAllowed by deps.settingsStartupReadsAllowed.collectAsStateWithLifecycle()
    LaunchedEffect(selectedSetting, settingsReadsAllowed) {
        if (settingsReadsAllowed && settingsDestinationReadsRadio(selectedSetting)) {
            deps.refreshDeviceSettings()
        }
    }

    val showsIndicator = AppTab.fromIndex(selectedTab).showsConnectionIndicator

    // The modal sheet presented above the shell: a tab-toolbar affordance or a staged deep link sets it.
    var sheet by remember { mutableStateOf<ShellSheet?>(null) }
    val showConnectPicker by deps.shouldShowConnectPicker.collectAsStateWithLifecycle()
    LaunchedEffect(showConnectPicker) {
        if (showConnectPicker) {
            sheet = RadioConnectEntry.sheet
            deps.consumeConnectPicker()
        }
    }
    val connectionChrome = ShellConnectionChromeState(
        visible = showsIndicator,
        connectionState = connectionState,
        battery = battery,
        batteryOcvArray = batteryOcvArray,
        nodeName = nodeName,
        clientRepeat = clientRepeat,
        onConnect = { sheet = RadioConnectEntry.sheet },
        onDisconnect = { scope.launchDisconnectAction { deps.actions.disconnect() } },
        onChangeDevice = { sheet = RadioConnectEntry.sheet },
        onSendAdvert = { flood -> deps.actions.sendAdvert(flood) },
        onAdvancedSettings = {
            deps.coordinator.selectTab(AppTab.SETTINGS)
            deps.coordinator.selectSetting(SettingsDestination.ADVANCED)
        },
    )
    val onSheet: (ShellSheet?) -> Unit = { sheet = it }
    ShellSendMessageDialogs(deps, scope)
    ShellSendAdvertDialogs(deps, scope)
    ShellStagedSheets(deps, sheet, onSheet)
    val onLinkTap: (LinkTarget) -> Unit = { target ->
        scope.handleShellLinkTap(target, deps, sheet, onSheet)
    }
    val currentTab = AppTab.fromIndex(selectedTab)
    val shellBackState = rememberShellBackState(
        sheet = sheet,
        currentTab = currentTab,
        selectedTool = selectedTool,
        selectedSetting = selectedSetting,
        backStack = backStacks[currentTab],
        onSheet = onSheet,
        onSystemBack = { deps.coordinator.handleSystemBack() },
        onClearStaging = { deps.coordinator.clearStagingFor(it) },
    )
    val pendingChatScroll = remember(deps.coordinator) { coordinatorChatScroll(deps.coordinator) }
    // iOS `AppState.bestAvailableLocation`: the phone's own GPS fix when known, else the connected
    // radio's coordinates. Distance sort, the "X away" row label and a trace's start and end hops all
    // read this order, so they agree on where the responder is.
    val deviceLocation: () -> LatLng? = { shellDeviceLocation(deps) }
    CompositionLocalProvider(
        LocalShellConnectionChrome provides connectionChrome,
        LocalInteractivePopActive provides (
            shellBackState.transitionState is NavigationEventTransitionState.InProgress
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AppShell(
                coordinator = deps.coordinator,
                unreadCount = unread,
                nodesContent = { ShellNodesTab(deps, deviceLocation, onSheet) },
                chatsContent = { ShellChatsTab(deps, onSheet) },
                chatDetailContent = { route ->
                    ShellChatDetail(route, deps, pendingChatScroll, deviceLocation, onLinkTap)
                },
                contactDetailContent = { contactId, fromDirectChat ->
                    ShellContactDetail(
                        contactId,
                        fromDirectChat,
                        deps,
                        connectionState,
                        networkOffline,
                        onSheet,
                    )
                },
                toolsContent = { ShellToolsTab(deps, connectionState, networkOffline, deviceLocation) },
                mapContent = { ShellMapTab(deps, connectionState, networkOffline, onSheet) },
                settingsContent = { ShellSettingsTab(deps, connectionState, networkOffline, onSheet) },
            )

            StatusPill(
                state = pill,
                onDisconnectedTap = { sheet = RadioConnectEntry.sheet },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = PILL_TOP_INSET),
            )

            DeviceMenuCoachMark(
                connectionState = connectionState,
                coachMarkStore = deps.coachMarkStore,
                showsIndicator = showsIndicator,
                scope = scope,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = COACH_MARK_TOP_INSET, end = COACH_MARK_END_INSET),
            )

            ShellSheetHost(
                deps = deps,
                sheet = sheet,
                onSheetChange = { sheet = it },
                onLinkTap = onLinkTap,
            )
        }
    }
}

@Composable
private fun ConnectionAlertDialog(deps: ShellDependencies) {
    val alert by deps.connectionAlert.collectAsStateWithLifecycle()
    alert?.let { ConnectionAlertContent(it, actions = deps.actions, onDismiss = deps.actions::dismissAlert) }
}

// A syncing-pill top inset matching the iOS overlay's 8pt top inset, added on top of the status-bar pad.
private val PILL_TOP_INSET = 8.dp

/** Below the small top app bar; end-aligned near the in-bar radio action. */
private val COACH_MARK_TOP_INSET = 56.dp
private val COACH_MARK_END_INSET = 8.dp
