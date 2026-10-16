package one.meshcore.shared.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.chat.matchesChatRoute
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.bestAvailableLocation
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.navigation.navigateToChannel
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.navigateToMap
import one.meshcore.shared.navigation.navigateToRoom
import one.meshcore.shared.remotenode.NodeAuthPurpose
import one.meshcore.shared.remotenode.fromContactType
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.PersistentLogger

internal fun shellCanHandleBack(
    sheet: ShellSheet?,
    currentTab: AppTab,
    selectedTool: ToolSelection?,
    selectedSetting: SettingsDestination?,
    backStack: List<*>?,
): Boolean = sheet != null ||
    (currentTab == AppTab.TOOLS && selectedTool != null) ||
    (currentTab == AppTab.SETTINGS && selectedSetting != null) ||
    backStack?.isNotEmpty() == true

/** A radio fix counts only when the device row actually stored coordinates. */
internal fun shellNodeFix(snapshot: LocationSettingsSnapshot?): LatLng? =
    snapshot?.takeIf { it.hasLocation }?.let { LatLng(it.latitude, it.longitude) }

internal fun shellDeviceLocation(deps: ShellDependencies): LatLng? = bestAvailableLocation(
    phoneLocation = deps.locationService?.currentLocation?.value,
    nodeLocation = shellNodeFix(deps.locationSettingsSnapshot()),
)

/** Chat contacts open the telemetry sheet directly. Repeaters and rooms authenticate first. */
internal fun contactTelemetrySheet(contact: ContactDto): ShellSheet? = when (contact.contactType) {
    ContactType.CHAT -> ShellSheet.NodeTelemetry(contact)
    else -> contactNodeAuthSheet(contact, NodeAuthPurpose.TELEMETRY)
}

internal fun contactNodeAuthSheet(contact: ContactDto, purpose: NodeAuthPurpose): ShellSheet? =
    RemoteNodeRole.fromContactType(contact.contactType)?.let { role ->
        ShellSheet.NodeAuth(contact, role, purpose)
    }

/**
 * Dispatches a synchronously routable link tapped inside a message: a coordinate centers the Map tab, a
 * `meshcore://` link goes through the deep-link router (which stages the add-contact / join-channel
 * confirmation), and a plain web link opens externally. Mentions and hashtags are resolved by
 * [handleShellLinkTap]. Returns the sheet to show, or the unchanged [current] sheet when the target
 * routes elsewhere.
 */
internal fun dispatchLinkTap(target: LinkTarget, deps: ShellDependencies, current: ShellSheet?): ShellSheet? =
    when (target) {
        is LinkTarget.Coordinate -> {
            deps.coordinator.navigateToMap(target.latitude, target.longitude)
            current
        }
        is LinkTarget.MeshCoreLink -> {
            deps.routeMeshCoreLink(target.url)
            current
        }
        is LinkTarget.Web -> {
            deps.openExternalUrl(target.url)
            current
        }
        // Mentions and hashtags take the async path in handleShellLinkTap; these branches are unreachable.
        is LinkTarget.Mention, is LinkTarget.Hashtag -> current
    }

/** True when this route is the same conversation the list row identifies. */
internal fun ChatRoute.matchesConversation(conversation: Conversation): Boolean = conversation.matchesChatRoute(this)

/** The matching row refreshes presentation and room state; unmatched-direct retention belongs to navigation. */
internal fun ChatRoute.refreshedPayload(conversations: List<Conversation>): ChatRoute? {
    val match = conversations.firstOrNull { matchesConversation(it) } ?: return null
    return when (this) {
        is ChatRoute.Direct -> copy(displayTitle = match.displayName)
        is ChatRoute.Channel -> copy(displayTitle = match.displayName)
        is ChatRoute.Room -> copy(
            displayTitle = match.displayName,
            sessionSnapshot = (match as Conversation.Room).session,
        )
    }
}

/** Routes a tapped conversation to the coordinator's kind-specific chat intent. */
internal fun NavigationCoordinator.openConversation(conversation: Conversation) = when (conversation) {
    is Conversation.Direct -> navigateToChat(
        conversation.id.value,
        displayTitle = conversation.displayName,
    )
    is Conversation.Channel -> navigateToChannel(
        conversation.id.value,
        displayTitle = conversation.displayName,
    )
    is Conversation.Room -> navigateToRoom(
        conversation.id.value,
        displayTitle = conversation.displayName,
        isConnected = conversation.session.isConnected,
    )
}

/**
 * Disconnect failures are already routed to the alert machine; cancellation still stops the UI action.
 */
@Suppress("TooGenericExceptionCaught")
internal fun CoroutineScope.launchDisconnectAction(block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PersistentLogger(this, category = "ConnectionManager")
                .warning("Disconnect action failed: ${error::class.simpleName}")
        }
    }
}
