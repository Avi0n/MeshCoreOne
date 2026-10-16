package one.meshcore.shared.room.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RemoteNodeEvent
import one.meshcore.shared.room.RoomServerEvent
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.room.startSessionKeepAlive
import one.meshcore.shared.room.stopSessionKeepAlive
import one.meshcore.shared.room.stopSessionKeepAliveAsync

/** Foreground and teardown for the room that is on screen. */
interface RoomConversationLifecycle {
    /**
     * The app returned to the foreground with this room on screen. Backgrounding stops every room
     * keep-alive without changing any session's connected flag, so no other signal can restart this one;
     * iOS restarts it from the room view's `scenePhase` observer for exactly that reason.
     */
    fun onEnterForeground()

    /**
     * Tears the conversation down: releases the notification slot so the room banners again, stops
     * keep-alive, and drops the event subscriptions. Both hand-offs run on their owner's longer-lived
     * coroutine, not the screen scope, because that scope is already being cancelled when the host
     * disposes.
     */
    fun dispose()
}

internal class RoomConversationPresence(
    private val scope: CoroutineScope,
    private val session: RemoteNodeSessionDto,
    private val dependencies: RoomConversationViewModel.Dependencies,
    private val model: RoomConversationModel,
) : RoomConversationLifecycle {
    private val controller get() = model.controller
    private var serverEventJob: Job? = null
    private var sessionEventJob: Job? = null
    private var servicesChangedJob: Job? = null
    private var initialLoadJob: Job? = null
    private var foregroundJob: Job? = null
    private var keepAliveSessionID: Uuid? = null
    private var isDisposed = false

    /** The service instance the two event subscriptions are attached to; identifies the live container. */
    private var boundService: RoomServerService? = null
    private var boundServicesChanged: Flow<Any?>? = null

    init {
        initialLoadJob = scope.launch {
            // Claim the slot before the first page lands, so a message arriving during the load is
            // suppressed rather than banner-posted for the room the user is already looking at.
            dependencies.notifications.setActiveRoom(session.id)
            controller.loadMessages(session)
            model.publish()
            startKeepAliveIfConnected()
        }
        observeServerEvents()
        observeSessionState()
        bindServicesChanged(dependencies.servicesChanged)
    }

    /**
     * Folds room-server events into the open conversation: an incoming message appends and reloads, a
     * delivery-status change reloads, and a connection recovery refreshes the session and restarts
     * keep-alive. iOS `.task(id: session.isConnected)` restarts keep-alive when
     * `connectionRecovered` bumps `sessionStateChangeCount`; the Kotlin session-event collector only
     * sees login/logout, not this inbound recover.
     */
    private fun observeServerEvents() {
        if (isDisposed) return
        serverEventJob?.cancel()
        val service = dependencies.roomServerService()
        boundService = service
        if (service == null) return
        serverEventJob = scope.launch {
            service.events.collect { event ->
                controller.handleServerEvent(event)
                model.publish()
                if (event is RoomServerEvent.ConnectionRecovered &&
                    event.sessionID == (controller.session?.id ?: session.id)
                ) {
                    restartKeepAlive()
                }
            }
        }
    }

    /**
     * Re-reads the session when its connection state changes, so login, logout, and a keep-alive-driven
     * disconnect move the composer between the input bar and the reconnect banner. Mirrors the iOS
     * `sessionStateChangeCount` observer on the room conversation.
     */
    private fun observeSessionState() {
        if (isDisposed) return
        sessionEventJob?.cancel()
        val service = dependencies.roomServerService() ?: return
        sessionEventJob = scope.launch {
            service.sessionEvents.collect { event ->
                when (event) {
                    is RemoteNodeEvent.SessionStateChanged -> {
                        if (event.sessionID != controller.session?.id) return@collect
                        controller.refreshSession()
                        model.publish()
                        if (event.isConnected) startKeepAliveIfConnected() else stopKeepAlive()
                    }
                }
            }
        }
    }

    /**
     * Follows the room to whichever service graph is live. A reconnect replaces the container, so the
     * subscriptions have to move with it and the login keep-alive has to run again on the new one; iOS
     * gets both for free because its room view re-reads `appState.services` at each use.
     */
    fun bindServicesChanged(flow: Flow<Any?>) {
        if (isDisposed || flow === boundServicesChanged) return
        boundServicesChanged = flow
        servicesChangedJob?.cancel()
        servicesChangedJob = scope.launch {
            flow.collect {
                if (dependencies.roomServerService() === boundService) return@collect
                observeServerEvents()
                observeSessionState()
                controller.refreshSession()
                model.publish()
                restartKeepAlive()
            }
        }
    }

    override fun onEnterForeground() {
        if (isDisposed) return
        foregroundJob?.cancel()
        foregroundJob = scope.launch { restartKeepAlive() }
    }

    /**
     * Restarts the loop whatever the latch says. The latch names a job the previous container or the
     * background stop already cancelled, so honouring it here would suppress the restart forever;
     * `RemoteNodeService.startSessionKeepAlive` stops any live loop first, so this cannot double up.
     */
    private suspend fun restartKeepAlive() {
        if (isDisposed) return
        keepAliveSessionID = null
        startKeepAliveIfConnected()
    }

    /**
     * Keeps the room session alive while its conversation is on screen. The Kotlin surface has no
     * `onDisappear`, so the host calls [dispose]; without the pair the session
     * goes stale and the `sync_since` force-resync hint is never sent.
     */
    private suspend fun startKeepAliveIfConnected() {
        if (isDisposed) return
        val live = controller.session ?: session
        // iOS keep-alive uses live `roomServerService`. OfflineDataAccess still returns one after
        // disconnect, so a stale `isConnected` flag would tick against a fake session.
        if (!live.isConnected || keepAliveSessionID == live.id || !dependencies.isRadioReady()) return
        val service = dependencies.roomServerService() ?: return
        service.startSessionKeepAlive(live.id, live.publicKey)
        keepAliveSessionID = live.id
    }

    private suspend fun stopKeepAlive() {
        val running = keepAliveSessionID ?: return
        keepAliveSessionID = null
        dependencies.roomServerService()?.stopSessionKeepAlive(running)
    }

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        initialLoadJob?.cancel()
        initialLoadJob = null
        foregroundJob?.cancel()
        foregroundJob = null
        serverEventJob?.cancel()
        serverEventJob = null
        sessionEventJob?.cancel()
        sessionEventJob = null
        servicesChangedJob?.cancel()
        servicesChangedJob = null
        boundServicesChanged = null
        dependencies.notifications.clearActiveRoomAsync(session.id)
        val running = keepAliveSessionID ?: return
        keepAliveSessionID = null
        dependencies.roomServerService()?.stopSessionKeepAliveAsync(running)
    }
}
