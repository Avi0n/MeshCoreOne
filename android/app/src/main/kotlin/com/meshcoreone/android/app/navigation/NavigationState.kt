// PortedFrom: MC1/State/NavigationCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/State/AppTab.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/MainSidebarView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Navigation/ChatRoute.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/ContactDetailView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotMap
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID

// PortedFrom: MC1/Views/Tools/ToolSelection.swift@db14559b39d32322b06477c6ae676112f583db50
enum class ToolSelection(val sourceName: String) {
    TRACE_PATH("tracePath"), LINE_OF_SIGHT("lineOfSight"), RX_LOG("rxLog"),
    NOISE_FLOOR("noiseFloor"), NODE_DISCOVERY("nodeDiscovery"), CLI("cli");

    val requiresRadio: Boolean get() = this != LINE_OF_SIGHT
    val prefersCollapsedSidebar: Boolean get() = this == LINE_OF_SIGHT || this == TRACE_PATH
}

// PortedFrom: MC1/Views/Settings/SettingsDetail.swift@db14559b39d32322b06477c6ae676112f583db50
enum class SettingsDetail(val sourceName: String, val requiresDevice: Boolean) {
    DEVICE_INFO("deviceInfo", true), RADIO("radio", true), LOCATION("location", true),
    CONNECTION("connection", true), ADVANCED("advanced", true),
    NOTIFICATIONS("notifications", false), CHATS("chats", false), APPEARANCE("appearance", false),
    MAPS("maps", false), LANGUAGE("language", false), BACKUP("backup", false),
    SUPPORT("support", false), FEEDBACK("feedback", false),
}

sealed interface ChatSelection {
    data class Direct(val contact: ContactDTO) : ChatSelection {
        override fun equals(other: Any?): Boolean = other is Direct && contact.radioId == other.contact.radioId && contact.id == other.contact.id
        override fun hashCode(): Int = 31 * contact.radioId.hashCode() + contact.id.hashCode()
    }
    data class Channel(val channel: ChannelDTO) : ChatSelection {
        override fun equals(other: Any?): Boolean = other is Channel && channel.radioId == other.channel.radioId && channel.id == other.channel.id
        override fun hashCode(): Int = 31 * channel.radioId.hashCode() + channel.id.hashCode()
    }
    data class Room(val session: RemoteNodeSessionDTO) : ChatSelection {
        override fun equals(other: Any?): Boolean = other is Room && session.radioId == other.session.radioId && session.id == other.session.id
        override fun hashCode(): Int = 31 * session.radioId.hashCode() + session.id.hashCode()
    }
}

sealed interface NavigationDestination {
    data class Root(val tab: AppTab) : NavigationDestination
    data class Chat(val selection: ChatSelection) : NavigationDestination
    data class ContactDetail(val contact: ContactDTO) : NavigationDestination
    data class RemoteNode(
        val contact: ContactDTO,
        val action: com.meshcoreone.android.feature.nodes.RemoteNodeAction,
    ) : NavigationDestination
    data object Discovery : NavigationDestination
    data class Tool(val selection: ToolSelection) : NavigationDestination
    data class Setting(val selection: SettingsDetail) : NavigationDestination
    data class Auxiliary(val feature: FeatureId) : NavigationDestination {
        init {
            require(feature.tab == null) { "A tab is not an auxiliary destination" }
        }
    }
}

// Native adaptation: Opaque entry identity; never a public key, DTO or radio ID in saved-state keys.
data class NavigationEntry(val id: Long, val destination: NavigationDestination)

// PortedFrom: MC1/State/MapFocusRequest.swift@db14559b39d32322b06477c6ae676112f583db50
data class MapFocusRequest(val latitude: Double, val longitude: Double) {
    val coordinate: Coordinate get() = Coordinate(latitude, longitude)
}
data class ContactLinkRequest(val name: String, val publicKey: Bytes, val type: ContactType)
data class ChannelLinkRequest(
    val name: String,
    val secret: Bytes,
    val regionScope: String? = null,
    val hasHashtagSecretMismatch: Boolean = false,
)
// PortedFrom: MC1/Views/Chats/HashtagDeeplinkSupport.swift@db14559b39d32322b06477c6ae676112f583db50
data class HashtagJoinRequest(val id: String)
data class PendingNotificationRoute(
    val id: Long, val generation: Long?, val request: NotificationNavigationRequest,
)

// Native adaptation: Material rail replaces the 64-point Apple sidebar; actual window width owns layout.
object NavigationLayout {
    const val RAIL_MIN_WIDTH_DP = 600
    const val RAIL_WIDTH_DP = 80
    const val CONTENT_MIN_WIDTH_DP = 380
    const val DETAIL_MIN_WIDTH_DP = 320
    const val TILE_MIN_WIDTH_DP = RAIL_WIDTH_DP + CONTENT_MIN_WIDTH_DP + DETAIL_MIN_WIDTH_DP

    fun usesRail(widthDp: Float): Boolean = widthDp >= RAIL_MIN_WIDTH_DP
    fun tilesListDetail(widthDp: Float): Boolean = widthDp >= TILE_MIN_WIDTH_DP
    fun showsRail(widthDp: Float, tool: ToolSelection?): Boolean =
        usesRail(widthDp) && tool?.prefersCollapsedSidebar != true
}

private fun initialStacks(): SnapshotMap<AppTab, SnapshotList<NavigationEntry>> =
    AppTab.entries.associateWith { tab ->
        listOf(NavigationEntry(tab.sourceIndex.toLong(), NavigationDestination.Root(tab))).snapshot()
    }.snapshotMap()

data class NavigationState(
    val selectedTab: AppTab = AppTab.CHATS,
    val stacks: SnapshotMap<AppTab, SnapshotList<NavigationEntry>> = initialStacks(),
    val nextEntryId: Long = AppTab.entries.size.toLong(),
    val tabBarVisible: Boolean = true,
    val widthDp: Float = 0f,
    val pendingChatContact: ContactDTO? = null,
    val chatsSelectedRoute: ChatSelection? = null,
    val pendingChannel: ChannelDTO? = null,
    val pendingRoomSession: RemoteNodeSessionDTO? = null,
    val pendingRoomAuthentication: RemoteNodeSessionDTO? = null,
    val pendingDiscoveryNavigation: Boolean = false,
    val pendingContactDetail: ContactDTO? = null,
    val selectedContact: ContactDTO? = null,
    val nodesShowingDiscovery: Boolean = false,
    val selectedTool: ToolSelection? = null,
    val selectedSetting: SettingsDetail? = null,
    val pendingScrollToMessageID: UUID? = null,
    val pendingDeviceMenuTipDonation: Boolean = false,
    val pendingMapFocus: MapFocusRequest? = null,
    val pendingContactLink: ContactLinkRequest? = null,
    val pendingChannelLink: ChannelLinkRequest? = null,
    val pendingHashtag: HashtagJoinRequest? = null,
    val radioId: RadioId? = null,
    val generation: Long = 0,
    val failure: NavigationFailure? = null,
    val navigationReady: Boolean = false,
    val pendingNotifications: SnapshotList<PendingNotificationRoute> = SnapshotList.empty(),
) {
    init {
        require(stacks.keys == AppTab.entries.toSet()) { "Every tab needs its own stack" }
        require(stacks.all { (tab, stack) ->
            stack.isNotEmpty() && stack.first().destination == NavigationDestination.Root(tab)
        }) { "Every stack must start at its matching root" }
        val ids = stacks.values.flatten().map { it.id } + pendingNotifications.map { it.id }
        require(ids.distinct().size == ids.size && ids.all { it >= 0 && it < nextEntryId }) {
            "Entry identities must be unique, nonnegative and below the next identity"
        }
        require(generation >= 0 && pendingNotifications.all { it.generation == null || it.generation >= 0 }) {
            "Connection generations must be nonnegative"
        }
        require(widthDp.isFinite() && widthDp >= 0f) { "Window width must be finite and nonnegative" }
    }
    val activeStack: SnapshotList<NavigationEntry> get() = stacks.getValue(selectedTab)
    val isSidebarWide: Boolean get() = NavigationLayout.tilesListDetail(widthDp)
    val isOnValidTabForDeviceMenuTip: Boolean
        get() = selectedTab == AppTab.CHATS || selectedTab == AppTab.NODES || selectedTab == AppTab.MAP
    val canGoBack: Boolean get() = activeStack.size > 1 || selectedTab != AppTab.CHATS

    internal fun select(tab: AppTab): NavigationState {
        val next = copy(selectedTab = tab, tabBarVisible = stacks.getValue(tab).size == 1)
        return if (NavigationLayout.usesRail(widthDp) && selectedTab == AppTab.TOOLS && tab != AppTab.TOOLS) {
            next.removeDestinations { it is NavigationDestination.Tool }.copy(selectedTool = null)
        } else next
    }

    internal fun push(tab: AppTab, destination: NavigationDestination): NavigationState {
        val base = select(tab)
        val stack = base.stacks.getValue(tab)
        if (stack.last().destination == destination) return base.copy(
            stacks = (base.stacks + (tab to
                (stack.dropLast(1) + stack.last().copy(destination = destination)).snapshot())).snapshotMap(),
            failure = null,
        )
        val entry = NavigationEntry(base.nextEntryId, destination)
        return base.copy(
            selectedTab = tab,
            stacks = (base.stacks + (tab to (stack + entry).snapshot())).snapshotMap(),
            nextEntryId = Math.incrementExact(base.nextEntryId),
            tabBarVisible = false,
            failure = null,
        )
    }

    internal fun removeDestinations(predicate: (NavigationDestination) -> Boolean): NavigationState {
        val nextStacks = stacks.mapValues { (_, stack) ->
            stack.filterNot { predicate(it.destination) }.snapshot()
        }.snapshotMap()
        return copy(stacks = nextStacks, tabBarVisible = nextStacks.getValue(selectedTab).size == 1)
    }
}

enum class NavigationTarget { CONTACT, CHANNEL, ROOM, REACTION }

sealed interface NavigationFailure {
    data class TargetNotFound(val kind: NavigationTarget) : NavigationFailure
    data class WrongTarget(val kind: NavigationTarget) : NavigationFailure
    data class WrongRadio(val expected: RadioId, val actual: RadioId) : NavigationFailure
    data object StaleGeneration : NavigationFailure
    data class Repository(val cause: com.meshcoreone.android.core.contracts.domain.PersistenceStoreError) : NavigationFailure
    data object UnsupportedNotification : NavigationFailure
    data object InvalidSavedState : NavigationFailure
    data class PrivateSelectionNotRestored(val count: Int) : NavigationFailure
}

sealed interface NavigationOutcome {
    data object Navigated : NavigationOutcome
    data object NotReady : NavigationOutcome
    data object NoPendingRoute : NavigationOutcome
    data class Failed(val failure: NavigationFailure) : NavigationOutcome
    data class Fallback(val failure: NavigationFailure) : NavigationOutcome
}
