// PortedFrom: MC1/ContentView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/AppSidebar.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/MainSidebarView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Sidebar/SidebarContentColumnBackground.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.NavDisplay
import com.meshcoreone.android.R
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.designsystem.themedCanvas
import com.meshcoreone.android.feature.chats.ChatsEntry
import com.meshcoreone.android.feature.map.MapEntry
import com.meshcoreone.android.feature.map.MapFeatureDependencies
import com.meshcoreone.android.feature.nodes.NodesEntry
import com.meshcoreone.android.feature.nodes.NodesDestination
import com.meshcoreone.android.feature.nodes.NodesNavigation
import com.meshcoreone.android.feature.nodes.RemoteNodeAction
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.onboarding.OnboardingEntry
import com.meshcoreone.android.feature.onboarding.OnboardingFeatureDependencies
import com.meshcoreone.android.feature.remotenodes.RemoteNodesEntry
import com.meshcoreone.android.feature.remotenodes.RemoteNodeLaunch
import com.meshcoreone.android.feature.remotenodes.RemoteNodeLaunchAction
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodesUiDependencies
import com.meshcoreone.android.feature.settings.SettingsEntry
import com.meshcoreone.android.feature.tools.ToolsEntry
import com.meshcoreone.android.feature.tools.ToolsFeatureDependencies
import com.meshcoreone.android.app.container.AppChatsFeature
import com.meshcoreone.android.feature.tools.los.LineOfSightEntry
import com.meshcoreone.android.feature.tools.los.LineOfSightFeatureDependencies
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as Contacts
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as Settings
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as Tools
import com.meshcoreone.android.core.l10n.R as Strings

internal fun AppTab.titleResource(): Int = when (this) {
    AppTab.CHATS -> Strings.string.tab_chats
    AppTab.NODES -> Strings.string.tab_nodes
    AppTab.MAP -> Strings.string.tab_map
    AppTab.TOOLS -> Strings.string.tab_tools
    AppTab.SETTINGS -> Strings.string.tab_settings
}

private fun AppTab.symbol(): MeshSymbol = when (this) {
    AppTab.CHATS -> MeshSymbol.MESSAGES
    AppTab.NODES -> MeshSymbol.NODES
    AppTab.MAP -> MeshSymbol.MAP
    AppTab.TOOLS -> MeshSymbol.TOOLS
    AppTab.SETTINGS -> MeshSymbol.SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun NativeNavigationShell(
    coordinator: NavigationCoordinator,
    modifier: Modifier = Modifier,
    unreadCount: Long = 0,
    /** Bound onboarding dependencies for the Settings "radio setup" route; null keeps the not-yet-ported shell. */
    onboarding: OnboardingFeatureDependencies? = null,
    map: MapFeatureDependencies? = null,
    tools: ToolsFeatureDependencies? = null,
    nodes: NodesFeatureDependencies? = null,
    lineOfSight: LineOfSightFeatureDependencies? = null,
    remoteNodes: RemoteNodesUiDependencies? = null,
    chats: AppChatsFeature? = null,
    settings: com.meshcoreone.android.app.container.AppSettingsFeature? = null,
    content: @Composable (NavigationDestination, (FeatureRoute) -> Unit) -> Unit = { destination, navigate ->
        ExistingFeatureContent(destination, navigate, onboarding, map, tools, nodes, lineOfSight, remoteNodes, chats, settings, coordinator)
    },
) {
    require(unreadCount >= 0) { "Unread count must be nonnegative" }
    val state by coordinator.state.collectAsStateWithLifecycle()
    val holders = AppTab.entries.associateWith { rememberSaveableStateHolder() }
    val owners = state.stacks.flatMap { (tab, stack) -> stack.map { it.id to tab } }.toMap()
    val liveContentKeys by rememberUpdatedState(owners.keys.map { "entry-$it" }.toSet())
    val decorator = remember(holders) {
        NavEntryDecorator<NavigationEntry>(
            onPop = { contentKey ->
                if (contentKey !in liveContentKeys) holders.values.forEach { it.removeState(contentKey) }
            },
            decorate = { entry ->
                val tab = checkNotNull(entry.metadata["ownerTab"] as? AppTab)
                holders.getValue(tab).SaveableStateProvider(entry.contentKey) { entry.Content() }
            },
        )
    }
    val labels = AppTab.entries.associateWith { stringResource(it.titleResource()) }
    val unreadDescription = if (unreadCount > 0) {
        stringResource(Strings.string.l10n_app_localizable_tabs_chatsunreadaccessibilityvalue, unreadCount)
    } else ""
    val snackbar = remember { SnackbarHostState() }
    val failureMessage = when (val failure = state.failure) {
        null -> null
        is NavigationFailure.Repository -> uiString(remember(failure) {
            UiErrorMapper().present(PersistenceStoreException(failure.cause)).content.message
        })
        is NavigationFailure.TargetNotFound -> stringResource(when (failure.kind) {
            NavigationTarget.CONTACT -> L.errorPersistenceContactNotFound
            NavigationTarget.CHANNEL -> L.errorPersistenceChannelNotFound
            NavigationTarget.ROOM -> L.errorPersistenceRemoteNodeSessionNotFound
            NavigationTarget.REACTION -> L.commonErrorFailedToLoad
        })
        is NavigationFailure.WrongTarget -> stringResource(L.errorPersistenceInvalidData)
        is NavigationFailure.WrongRadio, NavigationFailure.StaleGeneration -> stringResource(L.errorConnectionNotConnected)
        NavigationFailure.UnsupportedNotification -> stringResource(L.errorMeshCoreFeatureDisabled)
        NavigationFailure.InvalidSavedState -> stringResource(L.errorPersistenceInvalidData)
        is NavigationFailure.PrivateSelectionNotRestored -> stringResource(L.commonErrorFailedToLoad)
    }
    val dismissLabel = stringResource(L.commonOk)
    LaunchedEffect(state.failure) {
        if (failureMessage != null) {
            snackbar.showSnackbar(failureMessage, actionLabel = dismissLabel, withDismissAction = true)
            coordinator.clearFailure(state.failure)
        }
    }
    val motionScale = LocalMeshTheme.current.motionScale
    val predictiveTransition = remember(motionScale) {
        NavDisplay.predictivePopTransitionSpec {
            if (motionScale == 0f) EnterTransition.None togetherWith ExitTransition.None else null
        }
    }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        val width = maxWidth.value
        LaunchedEffect(width) { coordinator.measureWindow(width) }
        val useRail = NavigationLayout.usesRail(width)
        val hideNavigation = if (useRail) {
            state.selectedTab == AppTab.TOOLS && state.selectedTool?.prefersCollapsedSidebar == true
        } else !state.tabBarVisible
        val type = when {
            hideNavigation -> NavigationSuiteType.None
            useRail -> NavigationSuiteType.NavigationRail
            else -> NavigationSuiteType.ShortNavigationBarCompact
        }
        val navigationItems: @Composable () -> Unit = {
            AppTab.entries.forEach { tab ->
                val label = labels.getValue(tab)
                NavigationSuiteItem(
                    navigationSuiteType = type,
                    selected = state.selectedTab == tab,
                    onClick = { coordinator.selectTab(tab) },
                    modifier = Modifier
                        .heightIn(min = if (useRail) 72.dp else 80.dp * fontScale)
                        .testTag("tab:${tab.name}")
                        .semantics {
                            if (tab == AppTab.CHATS && unreadDescription.isNotEmpty()) stateDescription = unreadDescription
                        },
                    icon = { Icon(tab.symbol().vector, contentDescription = if (useRail) label else null) },
                    label = if (useRail) null else ({
                        Text(
                            label,
                            Modifier.fillMaxWidth().testTag("tab-label:${tab.name}"),
                            style = LocalTextStyle.current.copy(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto),
                            textAlign = TextAlign.Center,
                        )
                    }),
                    badge = if (tab == AppTab.CHATS && unreadCount > 0) ({
                        Badge(
                            Modifier.wrapContentWidth(unbounded = true).width(IntrinsicSize.Max)
                                .semantics { hideFromAccessibility() },
                        ) {
                            Text(
                                if (unreadCount > 99) stringResource(Chats.chatsScrollButtonBadgeOverflow) else unreadCount.toString(),
                                Modifier.testTag("navigation-unread-badge"),
                                maxLines = 1,
                            )
                        }
                    }) else null,
                )
            }
        }
        NavigationSuiteScaffold(
            modifier = Modifier.fillMaxSize().imePadding().testTag(
                if (hideNavigation) "navigation-hidden" else if (useRail) "navigation-rail" else "bottom-navigation",
            ),
            navigationSuiteType = type,
            navigationItems = {
                if (useRail) Column(Modifier.verticalScroll(rememberScrollState())) { navigationItems() }
                else navigationItems()
            },
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("navigation-error")) },
                topBar = {
                    TopAppBar(
                        expandedHeight = 64.dp * fontScale,
                        title = {
                            Text(
                                stringResource(state.selectedTab.titleResource()),
                                modifier = Modifier.fillMaxWidth().testTag("navigation-heading").semantics { heading() },
                            )
                        },
                        navigationIcon = {
                            if (state.canGoBack) {
                                IconButton(
                                    onClick = { coordinator.back() },
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("navigation-back"),
                                ) {
                                    Icon(painterResource(R.drawable.ic_back), stringResource(Strings.string.scaffold_back))
                                }
                            }
                        },
                        actions = {
                            if (state.selectedTab == AppTab.NODES) {
                                androidx.compose.material3.TextButton(
                                    { coordinator.navigate(FeatureRoute(FeatureId.REMOTE_NODES)) },
                                    Modifier.heightIn(min = 48.dp).testTag("open-remote-nodes"),
                                ) { Text(stringResource(com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings.remoteNodesAuthManagement)) }
                            }
                            IconButton(
                                onClick = { coordinator.navigate(FeatureRoute(FeatureId.ONBOARDING)) },
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("open-radio-setup"),
                            ) {
                                Icon(MeshSymbol.RADIO.vector, stringResource(Strings.string.scaffold_onboarding_title))
                            }
                        },
                    )
                },
            ) { padding ->
                val active = state.activeStack
                // A root on another tab has Chats behind it, so native predictive Back previews the real target.
                val backStack = if (state.selectedTab == AppTab.CHATS) active
                else state.stacks.getValue(AppTab.CHATS) + active
                val tileListDetail = NavigationLayout.tilesListDetail(width) &&
                    !(state.selectedTab == AppTab.TOOLS && state.selectedTool?.prefersCollapsedSidebar == true)
                val strategy = remember(tileListDetail) {
                    ListDetailStrategy(
                        tiled = tileListDetail,
                    )
                }
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                    entryDecorators = listOf(decorator),
                    sceneStrategies = listOf(strategy),
                    onBack = { coordinator.back() },
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(if (motionScale == 0f) 0 else 180),
                        ) togetherWith androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(if (motionScale == 0f) 0 else 180),
                        )
                    },
                    popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    entryProvider = { entry ->
                        NavEntry(
                            entry, contentKey = "entry-${entry.id}",
                            metadata = mapOf("destination" to entry.destination, "ownerTab" to owners.getValue(entry.id)) +
                                predictiveTransition,
                        ) {
                            Box(Modifier.fillMaxSize().focusRestorer().focusGroup().testTag("entry:${entry.id}")) {
                                content(entry.destination, coordinator::navigate)
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private class ListDetailStrategy(
    private val tiled: Boolean,
) : SceneStrategy<NavigationEntry> {
    override fun SceneStrategyScope<NavigationEntry>.calculateScene(
        entries: List<NavEntry<NavigationEntry>>,
    ): Scene<NavigationEntry>? {
        val tab = entries.last().metadata["ownerTab"] as? AppTab ?: return null
        if (tab == AppTab.MAP) return null
        val root = entries.lastOrNull { it.metadata["destination"] == NavigationDestination.Root(tab) } ?: return null
        val last = entries.last()
        val destination = last.metadata["destination"]
        if (destination is NavigationDestination.Auxiliary || destination is NavigationDestination.RemoteNode) return null
        val selected = if (last == root) listOf(root) else listOf(root, last)
        return ListDetailScene(last.contentKey, selected, entries.dropLast(1), tiled, tab)
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private data class ListDetailScene(
    override val key: Any,
    override val entries: List<NavEntry<NavigationEntry>>,
    override val previousEntries: List<NavEntry<NavigationEntry>>,
    private val tiled: Boolean,
    private val tab: AppTab,
) : Scene<NavigationEntry> {
    override val content: @Composable () -> Unit = {
        val hasDetail = entries.size == 2
        val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo()).copy(
            maxHorizontalPartitions = if (tiled) 2 else 1,
            horizontalPartitionSpacerSize = 0.dp,
            defaultPanePreferredWidth = NavigationLayout.CONTENT_MIN_WIDTH_DP.dp,
        )
        ListDetailPaneScaffold(
            modifier = Modifier.testTag(if (tiled) "list-detail" else "single-pane"),
            directive = directive,
            value = ThreePaneScaffoldValue(
                primary = if (hasDetail || tiled) PaneAdaptedValue.Expanded else PaneAdaptedValue.Hidden,
                secondary = if (!hasDetail || tiled) PaneAdaptedValue.Expanded else PaneAdaptedValue.Hidden,
                tertiary = PaneAdaptedValue.Hidden,
            ),
            listPane = {
                Box(
                    Modifier.preferredWidth(NavigationLayout.CONTENT_MIN_WIDTH_DP.dp)
                        .themedCanvas(LocalMeshTheme.current.frame).testTag("navigation-list-pane"),
                ) {
                    entries.first().Content()
                }
            },
            detailPane = {
                Box(Modifier.preferredWidth(NavigationLayout.DETAIL_MIN_WIDTH_DP.dp).testTag("navigation-detail-pane")) {
                    if (hasDetail) entries.last().Content()
                    else if (tiled) {
                        val prompt = when (tab) {
                            AppTab.CHATS -> Chats.chatsEmptyStateSelectConversation
                            AppTab.NODES -> Contacts.contactsListSelectNode
                            AppTab.TOOLS -> Tools.toolsSelectTool
                            AppTab.SETTINGS -> Settings.selectSetting
                            AppTab.MAP -> error("Map is not a list-detail section")
                        }
                        Box(Modifier.fillMaxSize().padding(24.dp).testTag("detail-empty")) {
                            Text(stringResource(prompt), modifier = Modifier.semantics { heading() })
                        }
                    }
                }
            },
        )
    }
}

// Native adaptation: Stable incumbent feature entries remain explicitly incomplete; no fabricated detail screen.
@Composable
private fun ExistingFeatureContent(
    destination: NavigationDestination,
    navigate: (FeatureRoute) -> Unit,
    onboarding: OnboardingFeatureDependencies?,
    map: MapFeatureDependencies?,
    tools: ToolsFeatureDependencies?,
    nodes: NodesFeatureDependencies?,
    lineOfSight: LineOfSightFeatureDependencies?,
    remoteNodes: RemoteNodesUiDependencies?,
    chats: AppChatsFeature?,
    settings: com.meshcoreone.android.app.container.AppSettingsFeature?,
    coordinator: NavigationCoordinator,
) {
    val navigationState by coordinator.state.collectAsStateWithLifecycle()
    val feature = when (destination) {
        is NavigationDestination.Root -> FeatureId.forTab(destination.tab)
        is NavigationDestination.Chat -> FeatureId.CHATS
        is NavigationDestination.ContactDetail, NavigationDestination.Discovery -> FeatureId.NODES
        is NavigationDestination.Tool -> FeatureId.TOOLS
        is NavigationDestination.Setting -> FeatureId.SETTINGS
        is NavigationDestination.Auxiliary -> destination.feature
        is NavigationDestination.RemoteNode -> FeatureId.REMOTE_NODES
    }
    val route = FeatureRoute(feature)
    when (feature) {
        FeatureId.CHATS -> if (chats != null) {
            AppChatsContent(chats, destination, coordinator)
        } else ChatsEntry(route, navigate)
        FeatureId.NODES -> NodesEntry(
            route = route,
            onNavigate = navigate,
            dependencies = nodes,
            destination = when (destination) {
                is NavigationDestination.ContactDetail -> NodesDestination.Detail(destination.contact)
                NavigationDestination.Discovery -> NodesDestination.Discovery
                else -> NodesDestination.List
            },
            navigation = object : NodesNavigation {
                override fun openContact(contact: com.meshcoreone.android.core.model.ContactDTO) =
                    coordinator.navigateToContactDetail(contact)

                override fun openDiscovery() = coordinator.navigateToDiscovery()
                override fun openChat(contact: com.meshcoreone.android.core.model.ContactDTO) =
                    coordinator.navigateToChat(contact)

                override fun openMap(latitude: Double, longitude: Double) =
                    coordinator.navigateToMap(latitude, longitude)

                override fun openRemoteNode(
                    contact: com.meshcoreone.android.core.model.ContactDTO,
                    action: RemoteNodeAction,
                ) = coordinator.navigateToRemoteNode(contact, action)

                override fun back() {
                    coordinator.back()
                }
            },
        )
        FeatureId.MAP -> {
            val focus = navigationState.pendingMapFocus?.let {
                com.meshcoreone.android.core.maps.GeoPoint(it.latitude, it.longitude)
            }
            MapEntry(route, navigate, map, focus, coordinator::clearPendingMapFocus)
        }
        FeatureId.TOOLS -> {
            val selected = (destination as? NavigationDestination.Tool)?.selection?.let {
                com.meshcoreone.android.feature.tools.navigation.ToolSelection.valueOf(it.name)
            }
            if (selected == com.meshcoreone.android.feature.tools.navigation.ToolSelection.LINE_OF_SIGHT &&
                lineOfSight != null
            ) {
                LineOfSightEntry(lineOfSight)
            } else {
                ToolsEntry(
                    route,
                    navigate,
                    selected,
                    { coordinator.navigateToTool(ToolSelection.valueOf(it.name)) },
                    tools,
                )
            }
        }
        FeatureId.SETTINGS -> if (settings != null) AppSettingsContent(settings, destination, coordinator)
            else SettingsEntry(route, navigate)
        FeatureId.ONBOARDING -> OnboardingEntry(
            route,
            // Leaving the auxiliary setup route pops it before moving on (completion targets the Chats root).
            { target -> coordinator.back(); navigate(target) },
            onboarding,
        )
        FeatureId.REMOTE_NODES -> RemoteNodesEntry(route, navigate, remoteNodes, cliContent = { session, send, enabled ->
            AppRemoteNodeCli(session, send, enabled)
        }, launch = (destination as? NavigationDestination.RemoteNode)?.let {
            RemoteNodeLaunch(
                it.contact,
                when (it.action) {
                    RemoteNodeAction.MANAGEMENT -> RemoteNodeLaunchAction.MANAGEMENT
                    RemoteNodeAction.TELEMETRY -> RemoteNodeLaunchAction.TELEMETRY
                    RemoteNodeAction.SAVED_HISTORY -> RemoteNodeLaunchAction.SAVED_HISTORY
                    RemoteNodeAction.JOIN_ROOM -> RemoteNodeLaunchAction.JOIN_ROOM
                },
            )
        }, onDismiss = { coordinator.back() }, onJoinRoom = {
            coordinator.back()
            coordinator.navigateToRoom(it)
        })
    }
}
