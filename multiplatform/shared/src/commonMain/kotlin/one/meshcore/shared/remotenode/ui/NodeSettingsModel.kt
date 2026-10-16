package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodeSettingsController
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.RepeaterSettingsController
import one.meshcore.shared.remotenode.regionMutationsAllowed
import kotlin.time.Duration

/** Teardown for the repeater settings screen. */
interface NodeSettingsLifecycle {
    /** Releases handlers and cancels model-owned work when its workspace is retired. */
    fun cleanup()

    fun settingsShown(): Long

    fun settingsHidden(token: Long)

    fun handleLateResponse(response: String)
}

internal class NodeSettingsModel(
    scope: CoroutineScope,
    private val sessionId: Uuid,
    internal val dependencies: NodeSettingsViewModel.Dependencies,
    nowEpochSeconds: () -> Long,
) : NodeSettingsLifecycle {
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val feedback = SettingsApplyFeedback(retained = !dependencies.ownsCLIHandler)
    internal val scope = CoroutineScope(scope.coroutineContext + ownerJob + feedback)
    internal val stateUpdates = MutableStateFlow(NodeSettingsState())
    val state: StateFlow<NodeSettingsState> = stateUpdates.asStateFlow()

    internal val deviceInfoRequest = RemoteSectionRequest(this.scope)
    internal val deviceInfoJob: Job? get() = deviceInfoRequest.job
    internal val identityRequest = RemoteSectionRequest(this.scope)
    internal val identityJob: Job? get() = identityRequest.job
    internal val radioRequest = RemoteSectionRequest(this.scope)
    internal val radioJob: Job? get() = radioRequest.job
    internal val contactInfoRequest = RemoteSectionRequest(this.scope)
    internal val contactInfoJob: Job? get() = contactInfoRequest.job
    internal val behaviorRequest = RemoteSectionRequest(this.scope)
    internal val behaviorJob: Job? get() = behaviorRequest.job
    internal lateinit var regionCommands: NodeRegionCommands

    private var isFetchingNodeInfo = false
    private var cliHandlerService: RepeaterAdminService? = null

    // The senders resolve the admin service per call: the screen outlives a radio drop, so editing a field
    // while disconnected must leave local state consistent rather than fail, and a reconnect must not keep
    // sending through the torn-down service.
    internal val controller: RepeaterSettingsController by lazy {
        val send: suspend (String, Duration) -> String = { command, timeout ->
            val service = requireService()
            currentRemoteRequest({
                ownerJob.isActive &&
                    dependencies.isCurrent() &&
                    dependencies.repeaterAdminService() === service
            }) {
                service.sendCommand(dependencies.sessionId() ?: sessionId, command, timeout)
            }
        }
        val sendRaw: suspend (String, Duration) -> String = { command, timeout ->
            val service = requireService()
            currentRemoteRequest({
                ownerJob.isActive &&
                    dependencies.isCurrent() &&
                    dependencies.repeaterAdminService() === service
            }) {
                service.sendRawCommand(dependencies.sessionId() ?: sessionId, command, timeout)
            }
        }
        RepeaterSettingsController(
            helper = NodeSettingsController(send, sendRaw, nowEpochSeconds),
            sendCommand = send,
            sendRawCommand = sendRaw,
        )
    }

    internal val helper: NodeSettingsController
        get() = controller.helper

    private fun requireService(): RepeaterAdminService =
        dependencies.repeaterAdminService() ?: throw RemoteNodeError.NotConnected

    internal fun start() {
        if (dependencies.ownsCLIHandler) registerLateResponseRecovery()
        // Firmware version, node name and owner info all arrive in one binary reply, so a section fetch
        // asks for them once instead of spending three CLI round-trips over the mesh.
        helper.onPreFetchNodeInfo = { fetchNodeInfo() }
    }

    /**
     * Fills firmware version, node name and owner info from the binary owner-info request. Best effort: a
     * failure leaves the fields blank for the per-section CLI fetch to fill.
     */
    internal suspend fun fetchNodeInfo() {
        val service = dependencies.repeaterAdminService() ?: return
        if (isFetchingNodeInfo) return
        isFetchingNodeInfo = true
        try {
            val response = currentRemoteRequest({
                ownerJob.isActive &&
                    dependencies.isCurrent() &&
                    dependencies.repeaterAdminService() === service
            }) {
                service.requestOwnerInfo(dependencies.sessionId() ?: sessionId)
            }
            helper.setNodeInfo(response.firmwareVersion, response.nodeName, response.ownerInfo)
            publishRecoveredFields()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The per-section CLI fetch is the fallback path.
        } finally {
            isFetchingNodeInfo = false
        }
    }

    /**
     * Adopts a CLI reply that arrives after its section request timed out, filling the blank field and
     * clearing that section's error. Recovery is free: the reply already crossed the mesh, so taking it
     * saves the airtime a refetch would spend. Mirrors the iOS `RepeaterSettingsViewModel` CLI handler.
     */
    private fun registerLateResponseRecovery() {
        val service = dependencies.repeaterAdminService() ?: return
        cliHandlerService = service
        service.setCLIHandler { message, _ ->
            // Fan-out runs before the waiter records this text. Leaving it to the waiter keeps a
            // timely flood-advert reply from filling an older unanswered advert interval.
            if (message.text == service.cliBodyClaimedByWaiter()) return@setCLIHandler
            handleLateResponse(message.text)
        }
    }

    override fun settingsShown(): Long = feedback.shown()

    override fun settingsHidden(token: Long) = feedback.hidden(token)

    override fun handleLateResponse(response: String) {
        if (!ownerJob.isActive || !dependencies.isCurrent()) return
        if (controller.handleLateResponse(response)) publishRecoveredFields()
    }

    override fun cleanup() {
        if (!ownerJob.isActive) return
        cliHandlerService?.setCLIHandler { _, _ -> }
        cliHandlerService = null
        ownerJob.cancel()
    }

    internal fun canMutateRegions(): Boolean {
        val current = stateUpdates.value
        return regionMutationsAllowed(
            regionsLoaded = current.regionsLoaded,
            isLoadingRegions = current.isLoadingRegions,
            isSavingRegions = current.isSavingRegions,
        )
    }

    /** Raises [ApplyBanner.NoService] and answers true when the admin session is gone, so callers stop. */
    internal fun reportedMissingService(): Boolean {
        if (!ownerJob.isActive) return true
        if (dependencies.repeaterAdminService() != null) return false
        stateUpdates.update { it.copy(applyBanner = ApplyBanner.NoService) }
        return true
    }
}
