package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid

/**
 * Drives the channel-info sheet (the Kotlin analogue of the iOS `ChannelInfoSheet`): the notification-level
 * and pin quick actions, the flood-scope picker, clear-messages, and delete-with-confirmation. Delete
 * and clear-messages route through [ChannelService]; the notification-level / pin / flood-scope writes
 * are injected persist closures (the offline-store and quick-action surfaces the shell owns), each applied
 * optimistically with rollback on failure — matching the iOS sheet.
 *
 * Mesh-region *discovery* (`requestRegions` probe) remains a separate port; the shell supplies
 * [Dependencies.knownRegions] / [Dependencies.deviceDefaultFloodScopeName] from the Device row so the
 * flood-scope picker lists already-known regions (backup import / prior discovery). This controller
 * persists the per-channel preference; the shell wires any radio flood-scope push.
 */
class ChannelInfoController internal constructor(
    val channel: ChannelDto,
    private val dependenciesProvider: () -> Dependencies,
) {
    constructor(channel: ChannelDto, dependencies: Dependencies) : this(channel, { dependencies })

    /** Live, per-connection inputs and the persist closures the sheet writes through. */
    // The sheet's live inputs plus its injected persist and region closures, grouped as the
    // view-model DI convention prescribes. Each is a distinct write surface the shell wires separately.
    data class Dependencies(
        val channelService: () -> ChannelService?,
        val radioId: () -> Uuid?,
        /** Persists a channel's notification level (optimistic; throws on failure to trigger rollback). */
        val persistNotificationLevel: suspend (ChannelDto, NotificationLevel) -> Unit,
        /** Persists a channel's pin flag. */
        val persistPinned: suspend (ChannelDto, Boolean) -> Unit,
        /** Persists a channel's flood-scope preference (and, when wired, pushes it to the radio). */
        val persistFloodScope: suspend (ChannelDto, ChannelFloodScope) -> Unit,
        /** The device's default flood-scope region name, for the "Use default" picker row. */
        val deviceDefaultFloodScopeName: () -> String? = { null },
        /** The device's known regions, for the named-region picker rows. */
        val knownRegions: () -> List<String> = { emptyList() },
        /**
         * Runs nearby mesh-region discovery and persists new names; returns a UI result for the status
         * line. Null until the shell wires [RegionDiscoveryService].
         */
        val discoverNearbyRegions: (suspend () -> RegionDiscoverUiResult)? = null,
        /** Localized status copy for [RegionDiscoverUiResult] (shell supplies L10n). */
        val regionDiscoverMessage: suspend (RegionDiscoverUiResult) -> String? = { null },
        /** Persists a manually-added or user-selected discovered region to Device.knownRegions. */
        val addKnownRegion: suspend (String) -> Unit = {},
        /** Removes a region from Device.knownRegions (clearing the default scope if it matched). */
        val removeKnownRegion: suspend (String) -> Unit = {},
        /**
         * Clears the channel's delivered tray notifications and refreshes the app badge after a delete or
         * clear-messages, the iOS `clearNotificationsForChannel(radioID:)`. Defaulted to a no-op on a
         * platform without a notification service wired.
         */
        val clearChannelNotifications: suspend (channelIndex: Int, radioID: Uuid) -> Unit = { _, _ -> },
        /**
         * Whether the radio is Ready. Notification level and pin are the iOS ChatViewModel
         * `.ready` gates; flood-scope persist stays local (iOS `offlineDataStore`) with a best-effort
         * radio push when a session exists.
         */
        val isRadioReady: () -> Boolean = { true },
    )

    private val pinMutex = Mutex()

    private val dependencies: Dependencies
        get() = dependenciesProvider()

    private val _state = MutableStateFlow(
        ChannelInfoState(
            notificationLevel = channel.notificationLevel,
            isPinned = channel.isPinned,
            floodScope = channel.floodScope,
            knownRegions = dependencies.knownRegions().distinct().sorted(),
        ),
    )
    val state: StateFlow<ChannelInfoState> = _state.asStateFlow()
    internal val regions = ChannelInfoRegions(dependenciesProvider, _state)

    /** The device's known regions (backup import / prior discovery), for the manage-regions screen. */
    fun knownRegions(): List<String> = _state.value.knownRegions

    /** The flood-scope picker rows for the current selection, device default, and known regions. */
    fun floodScopeItems(): List<FloodScopePickerItem> = ChannelFloodScopePicker.items(
        selected = _state.value.floodScope,
        deviceDefaultFloodScopeName = dependencies.deviceDefaultFloodScopeName(),
        knownRegions = _state.value.knownRegions,
    )

    /** Sets the notification level optimistically, rolling back if the persist throws. */
    suspend fun setNotificationLevel(level: NotificationLevel) {
        val currentDependencies = dependencies
        if (!currentDependencies.isRadioReady()) return
        applyOptimistic(
            read = { it.notificationLevel },
            write = { state, value -> state.copy(notificationLevel = value) },
            value = level,
        ) { currentDependencies.persistNotificationLevel(channel, level) }
    }

    /** Saves a local pin without a radio connection, rolling back on failure. */
    suspend fun setPinned(isPinned: Boolean) = pinMutex.withLock {
        val currentDependencies = dependencies
        val previous = _state.value.isPinned
        _state.update { it.copy(isPinned = isPinned) }
        var saved = false
        try {
            saved = one.meshcore.shared.runExceptionFallback({ false }) {
                currentDependencies.persistPinned(channel, isPinned)
                true
            }
            _state.update { it.copy(error = if (saved) null else ChannelActionError.PinSaveFailed) }
        } finally {
            if (!saved) _state.update { it.copy(isPinned = previous) }
        }
    }

    /** Selects a flood scope optimistically, rolling back if the persist throws. */
    suspend fun selectFloodScope(scope: ChannelFloodScope) {
        val currentDependencies = dependencies
        applyOptimistic(
            read = { it.floodScope },
            write = { state, value -> state.copy(floodScope = value) },
            value = scope,
        ) { currentDependencies.persistFloodScope(channel, scope) }
    }

    /**
     * Clears the channel's messages (keeping the channel), invoking [onCleared] on success. A missing
     * device/service or a failed write lands in the modal error.
     */
    suspend fun clearMessages(onCleared: () -> Unit) = runSlotAction(
        busy = { state, clearing -> state.copy(isClearingMessages = clearing) },
        onSuccess = onCleared,
    ) { service, radioId ->
        service.clearChannelMessages(radioId, channel.index)
    }

    /**
     * Deletes/leaves the channel (clears the slot on the radio and deletes the local row), invoking
     * [onDeleted] on success. A missing device/service or a failed write lands in the modal error.
     */
    suspend fun delete(onDeleted: () -> Unit) = runSlotAction(
        busy = { state, deleting -> state.copy(isDeleting = deleting) },
        onSuccess = onDeleted,
    ) { service, radioId ->
        service.clearChannel(radioId, channel.index)
    }

    private suspend fun <T> applyOptimistic(
        read: (ChannelInfoState) -> T,
        write: (ChannelInfoState, T) -> ChannelInfoState,
        value: T,
        persist: suspend () -> Unit,
    ) {
        val previous = read(_state.value)
        _state.update { write(it, value) }
        runOrRollback({ _state.update { write(it, previous) } }) {
            persist()
        }
    }

    private suspend fun runSlotAction(
        busy: (ChannelInfoState, Boolean) -> ChannelInfoState,
        onSuccess: () -> Unit,
        command: suspend (ChannelService, Uuid) -> Unit,
    ) {
        val currentDependencies = dependencies
        val service = currentDependencies.channelService()
        val radioId = currentDependencies.radioId()
        if (service == null || radioId == null) {
            _state.update { it.copy(error = channelUnavailableError(radioId)) }
            return
        }
        runChannelSlotCommand(
            begin = { _state.update { busy(it, true).copy(error = null) } },
            fail = { actionError -> _state.update { busy(it, false).copy(error = actionError) } },
            finish = {
                _state.update { busy(it, false) }
                onSuccess()
            },
        ) {
            command(service, radioId)
            currentDependencies.clearChannelNotifications(channel.index, radioId)
        }
    }

    // A store fault rolls the optimistic UI back and leaves no modal error. Cancellation leaves the
    // in-flight write. An Error rolls back and propagates.
    private suspend fun runOrRollback(rollback: () -> Unit, block: suspend () -> Unit) {
        runChannelPreferenceWrite(write = block, rollback = rollback)
    }
}

/**
 * Writes one channel preference. A store fault rolls the optimistic change back. Cancellation leaves
 * that change in place and propagates. An Error rolls back and propagates.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runChannelPreferenceWrite(write: suspend () -> Unit, rollback: () -> Unit) {
    try {
        write()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Error) {
        rollback()
        throw error
    } catch (_: Exception) {
        rollback()
    }
}
