package one.meshcore.shared.channel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.model.Uuid

/**
 * The channel-options hub state (the Kotlin analogue of the iOS `ChannelOptionsSheet`). Computed from the
 * live radio's channel list: [availableSlots] gates the create / join-private / scan-QR / join-hashtag
 * options, and [hasPublicChannel] gates join-public (slot 0 is already occupied). The footer copy branches on
 * the same two flags.
 */
data class ChannelOptionsState(
    val isLoading: Boolean = true,
    val availableSlots: List<Int> = emptyList(),
    val hasPublicChannel: Boolean = false,
    val loadUnavailable: Boolean = false,
) {
    /** No free user slot after a successful read. Disconnected/failed loads are not "full". */
    val noAvailableSlots: Boolean get() = !loadUnavailable && availableSlots.isEmpty()
}

/**
 * Drives the channel-options hub: on [load] it reads the channel list for the live radio, derives the free
 * slots and public-channel presence, and exposes them so the hub can gate each option. Read-only over the
 * providers; the sub-flows own the writes.
 */
class ChannelOptionsController(private val dependencies: Dependencies) {
    /** Live, per-connection inputs read on load; a null provider result means disconnected. */
    class Dependencies(
        val channelService: () -> ChannelService?,
        val radioId: () -> Uuid?,
        /** The device-reported channel-slot capacity, or null when unknown. */
        val maxChannels: () -> Int?,
    )

    private val _state = MutableStateFlow(ChannelOptionsState())
    val state: StateFlow<ChannelOptionsState> = _state.asStateFlow()

    /** The free slots the sub-flows target; read after [load] resolves. */
    val availableSlots: List<Int> get() = _state.value.availableSlots

    /** Loads the channel list and computes the free slots + public-channel presence. */
    suspend fun load() {
        _state.value = ChannelOptionsState()
        val service = dependencies.channelService()
        val radioId = dependencies.radioId()
        if (service == null || radioId == null) {
            _state.update {
                it.copy(
                    isLoading = false,
                    loadUnavailable = true,
                    availableSlots = emptyList(),
                    hasPublicChannel = false,
                )
            }
            return
        }
        val channels = service.readChannelsOrNull(radioId)
        if (channels == null) {
            // A failed read is not a full hub. Leave the options closed.
            _state.update {
                it.copy(
                    isLoading = false,
                    loadUnavailable = true,
                    availableSlots = emptyList(),
                    hasPublicChannel = false,
                )
            }
            return
        }
        val used = channels.map { it.index }.toSet()
        _state.update {
            it.copy(
                isLoading = false,
                loadUnavailable = false,
                availableSlots = ChannelSlots.availableSlots(used, dependencies.maxChannels() ?: 0),
                hasPublicChannel = ChannelSlots.hasPublicChannel(used),
            )
        }
    }
}
