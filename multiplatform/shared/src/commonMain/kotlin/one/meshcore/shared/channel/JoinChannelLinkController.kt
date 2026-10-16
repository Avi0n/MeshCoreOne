package one.meshcore.shared.channel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.MeshCoreDeepLink
import one.meshcore.shared.settings.PersistentLogger

/**
 * Drives the confirmation shown when a `meshcore://channel/add` link is tapped in a message (the Kotlin
 * analogue of the iOS `JoinChannelConfirmationSheet`). It loads the free slots, branches to
 * missing-device / no-slots / confirmation, and — unlike the hub sheets — treats a nil read-back as
 * [ChannelActionError.LoadFailed] rather than completing with a null channel. The deep-link router stages
 * the tapped [channel]; this controller consumes it.
 */
class JoinChannelLinkController(
    private val channel: MeshCoreDeepLink.Channel,
    private val dependencies: Dependencies,
) {
    /** Live, per-connection inputs read at load/join time; a null provider result means disconnected. */
    class Dependencies(
        val channelService: () -> ChannelService?,
        val radioId: () -> Uuid?,
        /** The device-reported channel-slot capacity, or null when unknown. */
        val maxChannels: () -> Int?,
        val floodPersistLogger: PersistentLogger? = null,
    )

    private val _state = MutableStateFlow(ChannelJoinSheetState())
    val state: StateFlow<ChannelJoinSheetState> = _state.asStateFlow()

    private var availableSlots: List<Int> = emptyList()

    /** The channel this sheet would join, for the confirmation header (name + truncated secret). */
    val target: MeshCoreDeepLink.Channel get() = channel

    /** Loads the free slots and resolves the phase; a missing radio short-circuits to missing-device. */
    suspend fun load() {
        availableSlots = emptyList()
        _state.update { it.copy(phase = ChannelJoinPhase.LOADING, error = null) }
        val loaded = readJoinSlots(dependencies.channelService(), dependencies.radioId(), dependencies.maxChannels())
        availableSlots = loaded.slots
        _state.update { it.copy(phase = loaded.phase) }
    }

    /** Joins the channel on the first free slot; a nil read-back surfaces [ChannelActionError.LoadFailed]. */
    suspend fun join(onJoined: (ChannelDto) -> Unit) {
        if (_state.value.isJoining) return
        val service = dependencies.channelService()
        val radioId = dependencies.radioId()
        if (service == null || radioId == null) {
            _state.update { it.copy(error = channelUnavailableError(radioId)) }
            return
        }
        val slot = availableSlots.firstOrNull()
        if (slot == null) {
            _state.update { it.copy(error = ChannelActionError.NoSlots) }
            return
        }

        _state.update { it.copy(isJoining = true, error = null) }
        val outcome = service.runChannelWrite(radioId, slot) {
            setChannelWithSecret(radioId, slot, channel.name, channel.secret)
        }
        when (outcome) {
            is ChannelWriteOutcome.Written -> {
                val joined = outcome.channel
                if (joined != null) {
                    val scoped = ChannelJoinFloodScopeApplier.applyIfNeeded(
                        joined,
                        channel.regionScope,
                        dependencies.floodPersistLogger,
                    ) { id, scope ->
                        service.setChannelFloodScope(id, scope)
                    }
                    _state.update { it.copy(isJoining = false, successTick = it.successTick + 1) }
                    onJoined(scoped)
                } else {
                    _state.update { it.copy(isJoining = false, error = ChannelActionError.LoadFailed) }
                }
            }
            is ChannelWriteOutcome.Failed -> _state.update { it.copy(isJoining = false, error = outcome.error) }
        }
    }
}

/**
 * Loads the free user slots for the live radio, returning an empty list when disconnected or on any fetch
 * failure (the iOS flows log and fall through to an empty slot set). Shared by the from-message join flows.
 */
internal suspend fun loadAvailableSlots(service: ChannelService?, radioId: Uuid?, maxChannels: Int?): List<Int> {
    if (service == null || radioId == null) return emptyList()
    val channels = service.readChannelsOrNull(radioId) ?: return emptyList()
    val used = channels.map { it.index }.toSet()
    return ChannelSlots.availableSlots(used, maxChannels ?: 0)
}

/** Resolves the from-message join phase: no radio → missing-device, no free slot → no-slots, else ready. */
internal fun resolvePhase(radioId: Uuid?, availableSlots: List<Int>): ChannelJoinPhase = when {
    radioId == null -> ChannelJoinPhase.MISSING_DEVICE
    availableSlots.isEmpty() -> ChannelJoinPhase.NO_SLOTS
    else -> ChannelJoinPhase.READY
}

/**
 * Reads the free slots for a from-message join. A missing radio reports [ChannelJoinPhase.MISSING_DEVICE]
 * with no slots so a retained confirmation cannot use availability from a retired radio.
 */
internal suspend fun readJoinSlots(service: ChannelService?, radioId: Uuid?, maxChannels: Int?): JoinSlotRead {
    if (service == null || radioId == null) return JoinSlotRead(ChannelJoinPhase.MISSING_DEVICE, slots = emptyList())
    val slots = loadAvailableSlots(service, radioId, maxChannels)
    return JoinSlotRead(resolvePhase(radioId, slots), slots)
}

internal data class JoinSlotRead(val phase: ChannelJoinPhase, val slots: List<Int>)
