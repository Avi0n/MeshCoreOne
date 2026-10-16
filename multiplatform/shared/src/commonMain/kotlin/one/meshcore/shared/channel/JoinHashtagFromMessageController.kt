package one.meshcore.shared.channel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.chat.text.HashtagUtilities
import one.meshcore.shared.model.Uuid

/**
 * Drives the join sheet shown when a hashtag is tapped in a message (the Kotlin analogue of the iOS
 * `JoinHashtagFromMessageView`): the tapped name
 * arrives as [HashtagTapResult.StageJoin][one.meshcore.shared.chat.text.HashtagTapResult.StageJoin]'s
 * `fullName`, this controller normalizes it, validates it, and joins by writing `setChannel(name = "#name",
 * passphrase = "#name")` — the same wire result as
 * [HashtagJoinSeam.deriveSecret][one.meshcore.shared.chat.text.HashtagJoinSeam.deriveSecret], keeping the
 * derived key identical to every other client that types the hashtag (spec §2.3). Consuming the seam here
 * does not touch the `chat` package.
 */
class JoinHashtagFromMessageController(rawChannelName: String, private val dependencies: Dependencies) {
    /** Live, per-connection inputs read at load/join time; a null provider result means disconnected. */
    class Dependencies(
        val channelService: () -> ChannelService?,
        val radioId: () -> Uuid?,
        val maxChannels: () -> Int?,
    )

    private val normalizedName = HashtagUtilities.normalizeHashtagName(rawChannelName)

    /** The full `#name` wire form shown in the confirmation header. */
    val fullChannelName: String = ChannelSecret.hashtagChannelString(normalizedName)

    private val _state = MutableStateFlow(ChannelJoinSheetState())
    val state: StateFlow<ChannelJoinSheetState> = _state.asStateFlow()

    private var availableSlots: List<Int> = emptyList()

    /** Loads the free slots and resolves the phase; a missing radio short-circuits to missing-device. */
    suspend fun load() {
        availableSlots = emptyList()
        _state.update { it.copy(phase = ChannelJoinPhase.LOADING, error = null) }
        val loaded = readJoinSlots(dependencies.channelService(), dependencies.radioId(), dependencies.maxChannels())
        availableSlots = loaded.slots
        _state.update { it.copy(phase = loaded.phase) }
    }

    /**
     * Joins the hashtag channel on the first free slot after re-validating the normalized name; a nil
     * read-back surfaces [ChannelActionError.LoadFailed], matching the iOS from-message flow.
     */
    suspend fun join(onJoined: (ChannelDto) -> Unit) {
        if (_state.value.isJoining) return
        val inputs = hashtagJoinInputs() ?: return

        val fullName = fullChannelName
        _state.update { it.copy(isJoining = true, error = null) }
        when (
            val outcome = inputs.service.runChannelWrite(inputs.radioId, inputs.slot) {
                setChannel(inputs.radioId, inputs.slot, fullName, fullName)
            }
        ) {
            is ChannelWriteOutcome.Written -> {
                val joined = outcome.channel
                if (joined != null) {
                    _state.update { it.copy(isJoining = false, successTick = it.successTick + 1) }
                    onJoined(joined)
                } else {
                    _state.update { it.copy(isJoining = false, error = ChannelActionError.LoadFailed) }
                }
            }
            is ChannelWriteOutcome.Failed -> _state.update { it.copy(isJoining = false, error = outcome.error) }
        }
    }

    private fun hashtagJoinInputs(): HashtagFromMessageJoin? {
        val service = dependencies.channelService()
        val radioId = dependencies.radioId()
        val slot = availableSlots.firstOrNull()
        if (service != null && radioId != null && slot != null) {
            if (HashtagUtilities.isValidHashtagName(normalizedName)) {
                return HashtagFromMessageJoin(service, radioId, slot)
            }
        }
        val error = when {
            service == null || radioId == null -> channelUnavailableError(radioId)
            slot == null -> ChannelActionError.NoSlots
            else -> ChannelActionError.InvalidName
        }
        _state.update { it.copy(error = error) }
        return null
    }

    private class HashtagFromMessageJoin(val service: ChannelService, val radioId: Uuid, val slot: Int)
}
