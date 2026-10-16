package one.meshcore.shared.channel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.chat.text.HashtagUtilities
import one.meshcore.shared.model.Uuid

/**
 * The join-hashtag-channel form state. [name] is the sanitized name **without** the leading `#` (the field
 * holds it bare and the UI shows a `#` label). [existingChannel] is set when a joined channel already matches
 * `#name` case-insensitively, which flips the button to "Go to #name" and shows the already-joined footer.
 */
data class JoinHashtagChannelState(
    val name: String = "",
    val isJoining: Boolean = false,
    val error: ChannelActionError? = null,
    val existingChannel: ChannelDto? = null,
) {
    /** The full `#name` wire form; both the channel name and its hashed passphrase carry the `#` (spec §2.3). */
    val fullChannelName: String get() = ChannelSecret.hashtagChannelString(name)

    /** The name passes hashtag validation. */
    val isValidName: Boolean get() = HashtagUtilities.isValidHashtagName(name)

    /** Join/Go is enabled with a valid name and — for a new channel — no write in flight. */
    val canJoin: Boolean get() = isValidName && !(isJoining && existingChannel == null)
}

/**
 * Drives the join-hashtag-channel flow (the Kotlin analogue of the iOS `JoinHashtagChannelView`): live
 * sanitizing of the name, case-insensitive matching against already-joined channels, and a join that writes
 * `setChannel(name = "#name", passphrase = "#name")` so any client typing the same hashtag derives the same
 * key. [loadExisting] refreshes the match set; a nil channel list fails open (allows creation).
 */
class JoinHashtagChannelController(private val dependencies: Dependencies) {
    /** Live, per-connection inputs read at join time; a null provider result means disconnected. */
    class Dependencies(
        val channelService: () -> ChannelService?,
        val radioId: () -> Uuid?,
        val availableSlots: () -> List<Int>,
    )

    private val _state = MutableStateFlow(JoinHashtagChannelState())
    val state: StateFlow<JoinHashtagChannelState> = _state.asStateFlow()

    /** Sets the name, sanitized live (lowercase, `[a-z0-9-]`, no leading hyphen), then re-matches existing. */
    fun setName(name: String) = _state.update {
        val sanitized = HashtagUtilities.sanitizeHashtagNameInput(name)
        it.copy(name = sanitized, existingChannel = matchExisting(sanitized, loadedChannels))
    }

    /** Loads the channels for the live radio so the existing-channel match can resolve; fails open on error. */
    suspend fun loadExisting() {
        loadedChannels = emptyList()
        _state.update { it.copy(existingChannel = null) }
        val service = dependencies.channelService() ?: return
        val radioId = dependencies.radioId() ?: return
        service.readChannelsOrNull(radioId)?.let { channels ->
            loadedChannels = channels
            _state.update { it.copy(existingChannel = matchExisting(it.name, channels)) }
        }
    }

    /**
     * Opens the existing matching channel if there is one, otherwise joins a new hashtag channel and invokes
     * [onJoined] with the resulting row (possibly null on a best-effort read-back miss).
     */
    suspend fun join(onJoined: (ChannelDto?) -> Unit) {
        val current = _state.value
        val existing = current.existingChannel
        if (!current.canJoin) return
        if (existing != null) {
            onJoined(existing)
            return
        }
        writeNewHashtagChannel(current.fullChannelName, onJoined)
    }

    private suspend fun writeNewHashtagChannel(fullName: String, onJoined: (ChannelDto?) -> Unit) {
        val service = dependencies.channelService()
        val radioId = dependencies.radioId()
        if (service == null || radioId == null) {
            _state.update { it.copy(error = channelUnavailableError(radioId)) }
            return
        }

        val slot = dependencies.availableSlots().firstOrNull() ?: ChannelSlots.USER_CHANNEL_MIN_SLOT
        _state.update { it.copy(isJoining = true, error = null) }
        when (val outcome = service.runChannelWrite(radioId, slot) { setChannel(radioId, slot, fullName, fullName) }) {
            is ChannelWriteOutcome.Written -> {
                _state.update { it.copy(isJoining = false) }
                onJoined(outcome.channel)
            }
            is ChannelWriteOutcome.Failed -> _state.update { it.copy(isJoining = false, error = outcome.error) }
        }
    }

    private var loadedChannels: List<ChannelDto> = emptyList()

    private fun matchExisting(name: String, channels: List<ChannelDto>): ChannelDto? {
        if (name.isEmpty()) return null
        val fullName = ChannelSecret.hashtagChannelString(name)
        return channels.firstOrNull { it.name.equals(fullName, ignoreCase = true) }
    }
}
