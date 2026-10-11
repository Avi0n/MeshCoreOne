// PortedFrom: MC1/State/NavigationCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/MainSidebarView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/ContactDetailView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// Native adaptation: Inject process repositories; this sink never constructs or owns a radio session.
interface NavigationLookup {
    suspend fun contact(key: EntityKey): ContactDTO?
    suspend fun channel(radioId: RadioId, index: UByte): ChannelDTO?
    suspend fun room(key: EntityKey): RemoteNodeSessionDTO?
}

class NavigationCoordinator(initial: NavigationState = NavigationState()) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<NavigationState> = mutableState.asStateFlow()
    private val notificationMutex = Mutex()
    private val drainMutex = Mutex()

    // AndroidOnly: WP-302 Called only by a newly created host, not on resize or connection changes.
    fun restoreHostState(restored: NavigationState) {
        check(mutableState.compareAndSet(NavigationState(), restored)) {
            "A live navigation host cannot be overwritten by restoration"
        }
    }

    // AndroidOnly: WP-407 Attach restored public screen state without overwriting live process radio/readiness.
    internal fun attachHostState(restored: NavigationState) = mutableState.update {
        if (restored == NavigationState() || it.stacks.values.any { stack -> stack.size > 1 } ||
            it.selectedTab != AppTab.CHATS || it.pendingMapFocus != null ||
            it.pendingContactLink != null || it.pendingChannelLink != null || it.pendingHashtag != null ||
            it.pendingNotifications.isNotEmpty() || it.pendingChatContact != null || it.pendingChannel != null ||
            it.pendingRoomSession != null || it.pendingRoomAuthentication != null || it.pendingContactDetail != null
        ) return@update it
        it.copy(
            selectedTab = restored.selectedTab,
            stacks = restored.stacks,
            nextEntryId = maxOf(it.nextEntryId, restored.nextEntryId),
            selectedSetting = restored.selectedSetting,
            selectedTool = restored.selectedTool,
            widthDp = restored.widthDp,
            tabBarVisible = restored.tabBarVisible,
            failure = restored.failure ?: it.failure,
        )
    }

    fun selectTab(tab: AppTab) = mutableState.update { it.select(tab) }
    fun measureWindow(widthDp: Float) {
        require(widthDp.isFinite() && widthDp >= 0f) { "Window width must be finite and nonnegative" }
        mutableState.update { it.copy(widthDp = widthDp) }
    }

    fun navigate(route: FeatureRoute) {
        val tab = route.feature.tab
        if (tab != null) selectTab(tab)
        else mutableState.update {
            it.push(it.selectedTab, NavigationDestination.Auxiliary(route.feature))
        }
    }

    fun navigateToChat(contact: ContactDTO, scrollToMessageID: UUID? = null) = mutableState.update {
        val selection = ChatSelection.Direct(contact)
        it.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
            pendingChatContact = contact, chatsSelectedRoute = selection,
            pendingScrollToMessageID = scrollToMessageID, tabBarVisible = false,
        )
    }

    fun navigateToChannel(channel: ChannelDTO, scrollToMessageID: UUID? = null) = mutableState.update {
        val selection = ChatSelection.Channel(channel)
        it.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
            pendingChannel = channel, chatsSelectedRoute = selection,
            pendingScrollToMessageID = scrollToMessageID, tabBarVisible = false,
        )
    }

    fun navigateToRoom(session: RemoteNodeSessionDTO) = mutableState.update {
        val selection = ChatSelection.Room(session)
        it.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
            pendingRoomSession = session, chatsSelectedRoute = selection, tabBarVisible = false,
        )
    }

    fun navigateToDiscovery() = mutableState.update {
        it.push(AppTab.NODES, NavigationDestination.Discovery).copy(
            pendingDiscoveryNavigation = true, nodesShowingDiscovery = true, tabBarVisible = true,
        )
    }

    fun navigateToContacts() = selectTab(AppTab.NODES)
    fun navigateToContactDetail(contact: ContactDTO) = mutableState.update {
        it.push(AppTab.NODES, NavigationDestination.ContactDetail(contact)).copy(
            pendingContactDetail = contact, selectedContact = contact, nodesShowingDiscovery = false,
        )
    }

    fun navigateToRemoteNode(contact: ContactDTO, action: com.meshcoreone.android.feature.nodes.RemoteNodeAction) =
        mutableState.update { it.push(AppTab.NODES, NavigationDestination.RemoteNode(contact, action)) }

    fun navigateToMap(latitude: Double, longitude: Double) = mutableState.update {
        it.select(AppTab.MAP).copy(pendingMapFocus = MapFocusRequest(latitude, longitude))
    }

    fun navigateToSetting(detail: SettingsDetail) = mutableState.update {
        it.push(AppTab.SETTINGS, NavigationDestination.Setting(detail)).copy(selectedSetting = detail)
    }

    fun navigateToTool(tool: ToolSelection) = mutableState.update {
        it.push(AppTab.TOOLS, NavigationDestination.Tool(tool)).copy(selectedTool = tool)
    }

    fun stageContactLink(link: ContactLinkRequest?) = mutableState.update { it.copy(pendingContactLink = link) }
    fun stageChannelLink(link: ChannelLinkRequest?) = mutableState.update { it.copy(pendingChannelLink = link) }
    fun stageHashtag(link: HashtagJoinRequest?) = mutableState.update { it.copy(pendingHashtag = link) }
    fun setPendingDeviceMenuTipDonation(pending: Boolean) =
        mutableState.update { it.copy(pendingDeviceMenuTipDonation = pending) }

    fun clearPendingNavigation() = mutableState.update { it.copy(pendingChatContact = null) }
    fun clearPendingRoomNavigation() = mutableState.update { it.copy(pendingRoomSession = null) }
    fun clearPendingRoomAuthentication() = mutableState.update { it.copy(pendingRoomAuthentication = null) }
    fun clearPendingChannelNavigation() = mutableState.update { it.copy(pendingChannel = null) }
    fun clearPendingDiscoveryNavigation() = mutableState.update { it.copy(pendingDiscoveryNavigation = false) }
    fun clearPendingScrollToMessage() = mutableState.update { it.copy(pendingScrollToMessageID = null) }
    fun clearPendingContactDetailNavigation() = mutableState.update { it.copy(pendingContactDetail = null) }
    fun clearPendingMapFocus() = mutableState.update { it.copy(pendingMapFocus = null) }
    fun clearPendingContactLink() = stageContactLink(null)
    fun clearPendingChannelLink() = stageChannelLink(null)
    fun clearPendingHashtag() = stageHashtag(null)
    fun clearFailure(expected: NavigationFailure? = state.value.failure) = mutableState.update {
        if (it.failure == expected) it.copy(failure = null) else it
    }

    // AndroidOnly: WP-302 WP303 signals real graph readiness; composition never claims readiness or performs lookup.
    fun setNavigationReady(ready: Boolean) = mutableState.update { it.copy(navigationReady = ready) }

    fun enqueueNotification(payload: NotificationPayload, manualAddContacts: Boolean = false): Long {
        val request = NotificationNavigationRequest.fromPayload(payload, manualAddContacts)
        while (true) {
            val current = state.value
            val generation = if (!current.navigationReady && current.radioId == null) null else current.generation
            val pending = PendingNotificationRoute(current.nextEntryId, generation, request)
            if (mutableState.compareAndSet(current, current.copy(
                nextEntryId = Math.incrementExact(current.nextEntryId),
                pendingNotifications = (current.pendingNotifications + pending).snapshot(),
            ))) return pending.id
        }
    }

    suspend fun consumePendingNotification(lookup: NavigationLookup): NavigationOutcome = drainMutex.withLock {
        val current = state.value
        if (!current.navigationReady) return@withLock NavigationOutcome.NotReady
        val pending = current.pendingNotifications.firstOrNull() ?: return@withLock NavigationOutcome.NoPendingRoute
        val outcome = handleNotificationRequest(pending.request, lookup, pending.generation ?: current.generation)
        // Cancellation before a committed transition propagates and leaves the request available for a real retry.
        mutableState.update {
            it.copy(pendingNotifications = it.pendingNotifications.filterNot { queued -> queued.id == pending.id }.snapshot())
        }
        outcome
    }

    fun clearPerDeviceSelection() = mutableState.update(::clearDeviceSelection)

    fun clearPerRadioSelection() = mutableState.update {
        clearRadioSelection(it).copy(generation = Math.incrementExact(it.generation))
    }

    fun clearPendingLinks() = mutableState.update {
        clearRadioSelection(it).copy(
            generation = Math.incrementExact(it.generation),
            pendingContactLink = null, pendingChannelLink = null, pendingHashtag = null,
        )
    }

    // AndroidOnly: WP-302 The process owner supplies generation changes; manual disconnect retains cached chats.
    fun replaceRadio(radioId: RadioId?) = mutableState.update {
        clearRadioSelection(it).copy(
            radioId = radioId, generation = Math.incrementExact(it.generation),
            pendingContactLink = null, pendingChannelLink = null, pendingHashtag = null,
            pendingChatContact = null, pendingChannel = null, pendingRoomSession = null,
            pendingRoomAuthentication = null, pendingContactDetail = null,
            pendingDiscoveryNavigation = false, pendingScrollToMessageID = null, pendingMapFocus = null,
        )
    }

    fun manuallyDisconnect() = mutableState.update {
        clearDeviceSelection(it).copy(generation = Math.incrementExact(it.generation))
    }

    fun back(): Boolean {
        while (true) {
            val current = mutableState.value
            if (!current.canGoBack) return false
            val next = if (current.activeStack.size == 1) current.select(AppTab.CHATS)
                else {
                    val stack = current.activeStack.dropLast(1).snapshot()
                    val destination = stack.last().destination
                    current.copy(
                        stacks = (current.stacks + (current.selectedTab to stack)).snapshotMap(),
                        tabBarVisible = stack.size == 1,
                        chatsSelectedRoute = if (current.selectedTab == AppTab.CHATS)
                            (destination as? NavigationDestination.Chat)?.selection else current.chatsSelectedRoute,
                        selectedContact = if (current.selectedTab == AppTab.NODES)
                            (destination as? NavigationDestination.ContactDetail)?.contact else current.selectedContact,
                        nodesShowingDiscovery = if (current.selectedTab == AppTab.NODES)
                            destination == NavigationDestination.Discovery else current.nodesShowingDiscovery,
                        selectedTool = if (current.selectedTab == AppTab.TOOLS)
                            (destination as? NavigationDestination.Tool)?.selection else current.selectedTool,
                        selectedSetting = if (current.selectedTab == AppTab.SETTINGS)
                            (destination as? NavigationDestination.Setting)?.selection else current.selectedSetting,
                        pendingChatContact = if (current.selectedTab == AppTab.CHATS) null else current.pendingChatContact,
                        pendingChannel = if (current.selectedTab == AppTab.CHATS) null else current.pendingChannel,
                        pendingRoomSession = if (current.selectedTab == AppTab.CHATS) null else current.pendingRoomSession,
                        pendingScrollToMessageID = if (current.selectedTab == AppTab.CHATS) null else current.pendingScrollToMessageID,
                        pendingContactDetail = if (current.selectedTab == AppTab.NODES) null else current.pendingContactDetail,
                        pendingDiscoveryNavigation = if (current.selectedTab == AppTab.NODES) false else current.pendingDiscoveryNavigation,
                    )
                }
            if (mutableState.compareAndSet(current, next)) return true
        }
    }

    suspend fun handleNotification(
        payload: NotificationPayload,
        lookup: NavigationLookup,
        manualAddContacts: Boolean = false,
        expectedGeneration: Long = state.value.generation,
    ): NavigationOutcome = handleNotificationRequest(
        NotificationNavigationRequest.fromPayload(payload, manualAddContacts), lookup, expectedGeneration,
    )

    suspend fun handleNotificationRequest(
        request: NotificationNavigationRequest,
        lookup: NavigationLookup,
        expectedGeneration: Long = state.value.generation,
    ): NavigationOutcome {
        return notificationMutex.withLock {
            val current = state.value
            if (current.generation != expectedGeneration) {
                return@withLock NavigationOutcome.Failed(NavigationFailure.StaleGeneration)
            }
            if (current.radioId != null && request.radioId != null && current.radioId != request.radioId) {
                return@withLock commitNotification(
                    Resolved.Failure(NavigationFailure.WrongRadio(current.radioId, requireNotNull(request.radioId))),
                    expectedGeneration,
                )
            }
            try {
                val resolved = resolve(request, lookup)
                currentCoroutineContext().ensureActive()
                commitNotification(resolved, expectedGeneration)
            } catch (error: PersistenceStoreException) {
                (error.cause as? CancellationException)?.let { throw it }
                currentCoroutineContext().ensureActive()
                val failure = NavigationFailure.Repository(error.error)
                commitNotification(
                    if (request is NotificationNavigationRequest.NewContact) Resolved.Fallback(failure)
                    else Resolved.Failure(failure),
                    expectedGeneration,
                )
            }
        }
    }

    private fun commitNotification(resolved: Resolved, generation: Long): NavigationOutcome {
        while (true) {
            val current = state.value
            if (current.generation != generation) {
                return NavigationOutcome.Failed(NavigationFailure.StaleGeneration)
            }
            val next = when (resolved) {
                    is Resolved.Chat -> {
                        val selection = ChatSelection.Direct(resolved.contact)
                        current.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
                            pendingChatContact = resolved.contact, chatsSelectedRoute = selection,
                            pendingScrollToMessageID = resolved.messageId, tabBarVisible = false,
                        )
                    }
                    is Resolved.Channel -> {
                        val selection = ChatSelection.Channel(resolved.channel)
                        current.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
                            pendingChannel = resolved.channel, chatsSelectedRoute = selection,
                            pendingScrollToMessageID = resolved.messageId, tabBarVisible = false,
                            failure = resolved.recoveryFailure,
                        )
                    }
                    is Resolved.Room -> {
                        if (resolved.session.isConnected) {
                            val selection = ChatSelection.Room(resolved.session)
                            current.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
                                pendingRoomSession = resolved.session, chatsSelectedRoute = selection, tabBarVisible = false,
                            )
                        } else {
                            current.select(AppTab.CHATS).copy(pendingRoomAuthentication = resolved.session, failure = null)
                        }
                    }
                    is Resolved.Contact -> current.push(
                        AppTab.NODES, NavigationDestination.ContactDetail(resolved.contact),
                    ).copy(pendingContactDetail = resolved.contact, selectedContact = resolved.contact, nodesShowingDiscovery = false)
                    Resolved.Discovery -> current.push(AppTab.NODES, NavigationDestination.Discovery).copy(
                        pendingDiscoveryNavigation = true, nodesShowingDiscovery = true, tabBarVisible = true,
                    )
                    is Resolved.Fallback -> current.select(AppTab.NODES).copy(failure = resolved.failure)
                    is Resolved.Failure -> current.copy(failure = resolved.failure)
            }
            if (mutableState.compareAndSet(current, next)) {
                return when (resolved) {
                    is Resolved.Fallback -> NavigationOutcome.Fallback(resolved.failure)
                    is Resolved.Failure -> NavigationOutcome.Failed(resolved.failure)
                    is Resolved.Channel -> resolved.recoveryFailure?.let { NavigationOutcome.Fallback(it) }
                        ?: NavigationOutcome.Navigated
                    else -> NavigationOutcome.Navigated
                }
            }
        }
    }

    private suspend fun resolve(
        request: NotificationNavigationRequest, lookup: NavigationLookup,
    ): Resolved = when (request) {
        is NotificationNavigationRequest.Direct -> resolveContact(lookup, request.contact)
        is NotificationNavigationRequest.NewContact -> if (request.manualAddContacts) Resolved.Discovery
        else {
            val resolved = resolveContact(lookup, request.contact)
            if (resolved is Resolved.Chat) Resolved.Contact(resolved.contact)
            else if (resolved is Resolved.Failure && resolved.failure is NavigationFailure.TargetNotFound)
                Resolved.Fallback(resolved.failure)
            else resolved
        }
        is NotificationNavigationRequest.Channel -> resolveChannel(lookup, request.radioId, request.index)
        is NotificationNavigationRequest.Reaction -> {
            var contactFailure: NavigationFailure.Repository? = null
            val contact = try {
                request.contact?.let { lookup.contact(it) }
            } catch (error: PersistenceStoreException) {
                (error.cause as? CancellationException)?.let { throw it }
                currentCoroutineContext().ensureActive()
                contactFailure = NavigationFailure.Repository(error.error)
                null
            }
            if (contact != null) {
                val key = requireNotNull(request.contact)
                val expected = key.radioId
                if (contact.radioId != expected) Resolved.Failure(NavigationFailure.WrongRadio(expected, contact.radioId))
                else if (contact.id != key.id) Resolved.Failure(NavigationFailure.WrongTarget(NavigationTarget.CONTACT))
                else Resolved.Chat(contact, request.messageId)
            } else if (request.channelRadioId != null && request.channelIndex != null) {
                val currentRadio = state.value.radioId
                if (currentRadio != null && currentRadio != request.channelRadioId)
                    Resolved.Failure(NavigationFailure.WrongRadio(currentRadio, request.channelRadioId))
                else when (val channel = resolveChannel(lookup, request.channelRadioId, request.channelIndex, request.messageId)) {
                    is Resolved.Channel -> channel.copy(recoveryFailure = contactFailure)
                    is Resolved.Failure -> if (channel.failure is NavigationFailure.TargetNotFound)
                        Resolved.Failure(contactFailure ?: channel.failure) else channel
                    else -> channel
                }
            } else Resolved.Failure(contactFailure ?: NavigationFailure.TargetNotFound(NavigationTarget.REACTION))
        }
        is NotificationNavigationRequest.Room -> lookup.room(request.session)?.let {
            if (it.radioId != request.session.radioId)
                Resolved.Failure(NavigationFailure.WrongRadio(request.session.radioId, it.radioId))
            else if (it.id != request.session.id) Resolved.Failure(NavigationFailure.WrongTarget(NavigationTarget.ROOM))
            else Resolved.Room(it)
        } ?: Resolved.Failure(NavigationFailure.TargetNotFound(NavigationTarget.ROOM))
        NotificationNavigationRequest.Unsupported -> Resolved.Failure(NavigationFailure.UnsupportedNotification)
    }

    private suspend fun resolveContact(lookup: NavigationLookup, key: EntityKey): Resolved =
        lookup.contact(key)?.let {
            if (it.radioId != key.radioId) Resolved.Failure(NavigationFailure.WrongRadio(key.radioId, it.radioId))
            else if (it.id != key.id) Resolved.Failure(NavigationFailure.WrongTarget(NavigationTarget.CONTACT))
            else Resolved.Chat(it)
        } ?: Resolved.Failure(NavigationFailure.TargetNotFound(NavigationTarget.CONTACT))

    private suspend fun resolveChannel(
        lookup: NavigationLookup, radioId: RadioId, index: UByte, messageId: UUID? = null,
    ): Resolved = lookup.channel(radioId, index)?.let {
        if (it.radioId != radioId) Resolved.Failure(NavigationFailure.WrongRadio(radioId, it.radioId))
        else if (it.index != index) Resolved.Failure(NavigationFailure.WrongTarget(NavigationTarget.CHANNEL))
        else Resolved.Channel(it, messageId)
    } ?: Resolved.Failure(NavigationFailure.TargetNotFound(NavigationTarget.CHANNEL))

    private sealed interface Resolved {
        data class Chat(val contact: ContactDTO, val messageId: UUID? = null) : Resolved
        data class Channel(
            val channel: ChannelDTO, val messageId: UUID? = null,
            val recoveryFailure: NavigationFailure.Repository? = null,
        ) : Resolved
        data class Room(val session: RemoteNodeSessionDTO) : Resolved
        data class Contact(val contact: ContactDTO) : Resolved
        data object Discovery : Resolved
        data class Fallback(val failure: NavigationFailure) : Resolved
        data class Failure(val failure: NavigationFailure) : Resolved
    }

    private companion object {
        fun clearDeviceSelection(state: NavigationState): NavigationState {
            val retained = state.removeDestinations {
                (it is NavigationDestination.Tool && it.selection.requiresRadio) ||
                    (it is NavigationDestination.Setting && it.selection.requiresDevice)
            }
            return retained.copy(
                selectedTool = if (state.selectedTool?.requiresRadio == true) {
                    retained.stacks.getValue(AppTab.TOOLS)
                        .mapNotNull { (it.destination as? NavigationDestination.Tool)?.selection }.lastOrNull()
                } else state.selectedTool,
                selectedSetting = if (state.selectedSetting?.requiresDevice == true) {
                    retained.stacks.getValue(AppTab.SETTINGS)
                        .mapNotNull { (it.destination as? NavigationDestination.Setting)?.selection }.lastOrNull()
                } else state.selectedSetting,
            )
        }

        fun clearRadioSelection(state: NavigationState): NavigationState =
            clearDeviceSelection(state).removeDestinations {
                it is NavigationDestination.Chat || it is NavigationDestination.ContactDetail ||
                    it is NavigationDestination.RemoteNode ||
                    it == NavigationDestination.Discovery
            }.copy(selectedContact = null, nodesShowingDiscovery = false, chatsSelectedRoute = null)
    }
}
