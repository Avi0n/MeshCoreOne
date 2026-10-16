package one.meshcore.shared.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.ChatScrollRequest
import one.meshcore.shared.layout.SidebarLayout
import one.meshcore.shared.layout.SidebarVisibility
import one.meshcore.shared.prefs.PreferencesStore

/**
 * A destination pushed onto a tab's back stack in the compact (phone) shell. The wide shell reads the
 * hoisted selection fields instead; a navigate intent writes both so either shell renders the same
 * target. Kept identity-keyed for the same reason as [ChatRoute].
 */
sealed interface NavKey {
    /** A chat conversation opened above the Chats list. */
    data class ChatDetail(val route: ChatRoute) : NavKey

    /**
     * A contact's detail. On the Nodes tab this is the list's pushed destination; on Chats it is the
     * in-thread Info overlay ([fromDirectChat]), matching iOS `ChatConversationInfoSheet`.
     */
    data class ContactDetail(val contactId: String, val fromDirectChat: Boolean = false) : NavKey

    /** The node-discovery screen opened above the Nodes list. */
    data object Discovery : NavKey

    /** The blocked-contacts list opened above the Nodes list. */
    data object BlockedContacts : NavKey

    /**
     * A map focus opened from another tab (coordinate tap / geo deep link). Acts as a back-stack
     * marker so system Back can return to the originating tab; the map root content still renders.
     */
    data object MapFocus : NavKey
}

/**
 * The Kotlin analogue of the iOS `NavigationCoordinator`: the single owner of tab selection, per-tab
 * back stacks, and the pending navigation targets a producer sets and a shell consumes. It is a plain
 * state holder over an injected [scope] and exposes `StateFlow`s the Compose shell observes; it holds no
 * Compose or platform types so it is fully unit-testable on the desktop JVM.
 *
 * Two layout facts drive the adaptive shell: [selectedTab] (the persisted integer index deep links and
 * notification taps write) and [sidebarVisibility], derived from the measured-width [isSidebarWide] flag,
 * the selected section, and any open tool. Selecting a tab is a single write to [selectedTab]; the
 * collapsed shape follows in the derived flow rather than a second stored value, so a section can never
 * render at the wrong shape for a frame (the iPad trap the iOS shell guards against with a same-write
 * collapse).
 */
class NavigationCoordinator(private val scope: CoroutineScope, private val preferences: PreferencesStore? = null) {
    internal val selectedTabIndex = MutableStateFlow(AppTab.CHATS.index)

    /** The selected tab index. Persisted across process death when a [PreferencesStore] is injected. */
    val selectedTab: StateFlow<Int> = selectedTabIndex.asStateFlow()

    internal val sidebarWide = MutableStateFlow(false)

    /**
     * Whether the container measured at the shell is wide enough to tile all three columns. Defaults to
     * false so an unmeasured layout takes the safe collapse branch rather than claiming wide while narrow.
     */
    val isSidebarWide: StateFlow<Boolean> = sidebarWide.asStateFlow()

    internal val tabBarVisibleState = MutableStateFlow(true)

    /** Whether the bottom tab bar is shown; hidden before a chat push so it does not flash during it. */
    val tabBarVisible: StateFlow<Boolean> = tabBarVisibleState.asStateFlow()

    internal val selectedToolState = MutableStateFlow<ToolSelection?>(null)
    internal var cliInitialKeyboardFocusClaimed = false

    /** The open tool on the Tools tab, shared so the wide shell's content and detail agree. */
    val selectedTool: StateFlow<ToolSelection?> = selectedToolState.asStateFlow()

    internal val selectedSettingState = MutableStateFlow<SettingsDestination?>(null)

    /** The open settings page, shared so the wide shell's content and detail agree. */
    val selectedSetting: StateFlow<SettingsDestination?> = selectedSettingState.asStateFlow()

    /**
     * The column arrangement the wide shell renders, derived from width, section, and open tool. A tool
     * that prefers a collapsed sidebar overrides width; otherwise a wide container tiles and a narrow one
     * shows the section's collapsed shape. Recomputed atomically from [selectedTab] so a tab change and
     * its collapse happen in one observable step.
     */
    val sidebarVisibility: StateFlow<SidebarVisibility> =
        combine(sidebarWide, selectedTabIndex, selectedToolState) { wide, tabIndex, tool ->
            val tab = AppTab.fromIndex(tabIndex)
            val toolCollapses = tab == AppTab.TOOLS && tool?.prefersCollapsedSidebar == true
            SidebarLayout.sidebarVisibility(
                isWide = wide,
                toolCollapsesSidebar = toolCollapses,
                sectionCollapsed = tab.collapsedSidebarVisibility,
            )
        }.stateIn(scope, SharingStarted.Eagerly, SidebarVisibility.ContentDetail)

    internal val backStackState = MutableStateFlow<Map<AppTab, List<NavKey>>>(emptyMap())

    /** The pushed destinations per tab; an empty list means the tab is at its root list. */
    val backStacks: StateFlow<Map<AppTab, List<NavKey>>> = backStackState.asStateFlow()

    internal val chatsRouteState = MutableStateFlow<ChatRoute?>(null)

    /** The Chats detail route, shared so the wide shell's content and detail columns agree. */
    val chatsSelectedRoute: StateFlow<ChatRoute?> = chatsRouteState.asStateFlow()

    internal val selectedContactState = MutableStateFlow<String?>(null)

    /** The selected contact on the Nodes tab, kept in memory only and never persisted (identity-bearing). */
    val selectedContactId: StateFlow<String?> = selectedContactState.asStateFlow()

    internal val nodesDiscoveryState = MutableStateFlow(false)

    /** Whether the Nodes detail is showing Discovery rather than a contact. */
    val nodesShowingDiscovery: StateFlow<Boolean> = nodesDiscoveryState.asStateFlow()

    internal val nodesBlockedState = MutableStateFlow(false)

    /** Whether the Nodes tab is showing the blocked-contacts list. */
    val nodesShowingBlocked: StateFlow<Boolean> = nodesBlockedState.asStateFlow()

    /**
     * Tab index to restore when [NavKey.MapFocus] is popped. Null when map was opened without a
     * cross-tab origin (or after return was consumed). Not observed by Compose — only the back stack is.
     */
    internal var mapReturnTabIndex: Int? = null

    internal val pendingScrollState = MutableStateFlow<ChatScrollRequest?>(null)

    /** A route-bound reaction notification, consumed only after its target can be shown. */
    val pendingScrollTarget: StateFlow<ChatScrollRequest?> = pendingScrollState.asStateFlow()

    internal val pendingMapFocusState = MutableStateFlow<MapFocusRequest?>(null)

    /** A coordinate the Map tab should drop a pin on and center, consumed once. */
    val pendingMapFocus: StateFlow<MapFocusRequest?> = pendingMapFocusState.asStateFlow()

    internal val droppedMapPinState = MutableStateFlow<MapFocusRequest?>(null)

    /**
     * The live dropped pin. Survives leaving the Map tab (iOS keeps `MapViewModel.focusedPin`).
     * Cleared only by the callout Clear action, not by consuming [pendingMapFocus].
     */
    val droppedMapPin: StateFlow<MapFocusRequest?> = droppedMapPinState.asStateFlow()

    internal val pendingContactLinkState = MutableStateFlow<MeshCoreDeepLink.Contact?>(null)

    /** An add-contact confirmation staged by a `meshcore://contact/add` link, consumed by the sheet. */
    val pendingContactLink: StateFlow<MeshCoreDeepLink.Contact?> = pendingContactLinkState.asStateFlow()

    internal val pendingChannelLinkState = MutableStateFlow<MeshCoreDeepLink.Channel?>(null)

    /** A join-channel confirmation staged by a `meshcore://channel/add` link, consumed by the sheet. */
    val pendingChannelLink: StateFlow<MeshCoreDeepLink.Channel?> = pendingChannelLinkState.asStateFlow()

    internal val pendingHashtagState = MutableStateFlow<String?>(null)

    /**
     * A raw hashtag channel name staged by a `meshcoreone://hashtag/<name>` link whose channel this radio
     * has not joined — the Kotlin analogue of iOS `pendingHashtag`. The shell presents the join-from-message
     * confirmation for it. A link naming a channel already stored opens that channel instead of staging.
     */
    val pendingHashtagJoin: StateFlow<String?> = pendingHashtagState.asStateFlow()

    internal val pendingSendMessageState = MutableStateFlow<MeshCoreDeepLink.SendMessage?>(null)

    /** A Shortcuts send staged by `meshcore://send`, consumed by the enqueue path. */
    val pendingSendMessage: StateFlow<MeshCoreDeepLink.SendMessage?> = pendingSendMessageState.asStateFlow()

    internal val pendingSendAdvertState = MutableStateFlow<MeshCoreDeepLink.SendAdvert?>(null)

    /** A Shortcuts self-advert staged by `meshcore://advert`, consumed by the send-advert path. */
    val pendingSendAdvert: StateFlow<MeshCoreDeepLink.SendAdvert?> = pendingSendAdvertState.asStateFlow()

    internal val pendingRoomAuthState = MutableStateFlow<String?>(null)

    /**
     * A room session id staged for the auth sheet by a [navigateToRoom] call whose session was not
     * connected — the Kotlin analogue of iOS `pendingRoomAuthentication`. The shell resolves the session
     * and its contact and presents [one.meshcore.shared.ui.ShellSheet.RoomAuth].
     */
    val pendingRoomAuth: StateFlow<String?> = pendingRoomAuthState.asStateFlow()

    internal val radioSheetEpochState = MutableStateFlow(0)

    /**
     * Bumps when a radio-scoped teardown runs so the shell can drop copied [one.meshcore.shared.ui.ShellSheet]
     * state. iOS binds those sheets to coordinator fields or to `selectedContact`; Android copies them into
     * `remember`, so a nil write alone leaves the sheet up.
     */
    val radioSheetEpoch: StateFlow<Int> = radioSheetEpochState.asStateFlow()

    init {
        if (preferences != null) {
            scope.launch {
                try {
                    preferences.getInt(SELECTED_TAB_KEY)?.let { restored ->
                        if (selectedSettingState.value == null && AppTab.entries.any { it.index == restored }) {
                            selectedTabIndex.value = restored
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A DataStore read fault must not skip the tab-persist collector.
                }
                selectedTabIndex.collect { tab ->
                    try {
                        preferences.putInt(SELECTED_TAB_KEY, tab)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // iOS UserDefaults does not throw; keep later tab writes.
                    }
                }
            }
        }
    }

    /** Tabs where the BLE status indicator lives and the device-menu tip can anchor (chats/nodes/map). */
    val isOnValidTabForDeviceMenuTip: Boolean
        get() = AppTab.fromIndex(selectedTabIndex.value).showsConnectionIndicator

    companion object {
        /**
         * The persisted selected-tab key. Android-new: iOS re-derives the tab from state restoration and
         * notification taps rather than persisting it, so there is no iOS `UserDefaults` key to mirror;
         * Android persists it explicitly to survive process death. Namespaced to avoid colliding with the
         * iOS-mirrored keys in `PersistenceKeys`.
         */
        const val SELECTED_TAB_KEY = "nav.selectedTab"

        /** Soft cap for stacked chat destinations (chat A → B → …). */
        const val MAX_CHAT_BACK_STACK_DEPTH: Int = 8
    }
}
