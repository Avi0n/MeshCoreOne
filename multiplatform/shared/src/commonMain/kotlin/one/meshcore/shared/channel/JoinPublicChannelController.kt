package one.meshcore.shared.channel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid

/** The join-public-channel state: a single one-shot action with a spinner and a modal error. */
data class JoinPublicChannelState(val isJoining: Boolean = false, val error: ChannelActionError? = null)

/**
 * Drives the one-button re-add of the public channel on slot 0 (the Kotlin analogue of the iOS
 * `JoinPublicChannelView`): calls [ChannelService.setupPublicChannel] and returns the slot-0 row. The public
 * channel's fixed name and secret live in [ChannelSecret]; this only sequences the write and read-back.
 */
class JoinPublicChannelController(private val dependencies: Dependencies) {
    /** Live, per-connection inputs read at join time; a null provider result means disconnected. */
    class Dependencies(val channelService: () -> ChannelService?, val radioId: () -> Uuid?)

    private val _state = MutableStateFlow(JoinPublicChannelState())
    val state: StateFlow<JoinPublicChannelState> = _state.asStateFlow()

    /** Re-adds the public channel and invokes [onJoined] with the slot-0 row (possibly null on read-back miss). */
    suspend fun join(onJoined: (ChannelDto?) -> Unit) {
        if (_state.value.isJoining) return
        val service = dependencies.channelService()
        val radioId = dependencies.radioId()
        if (service == null || radioId == null) {
            _state.update { it.copy(error = channelUnavailableError(radioId)) }
            return
        }
        _state.update { it.copy(isJoining = true, error = null) }
        when (
            val outcome = service.runChannelWrite(radioId, ChannelSecret.PUBLIC_CHANNEL_SLOT) {
                setupPublicChannel(radioId)
            }
        ) {
            is ChannelWriteOutcome.Written -> {
                _state.update { it.copy(isJoining = false) }
                onJoined(outcome.channel)
            }
            is ChannelWriteOutcome.Failed ->
                _state.update { it.copy(isJoining = false, error = outcome.error) }
        }
    }
}
