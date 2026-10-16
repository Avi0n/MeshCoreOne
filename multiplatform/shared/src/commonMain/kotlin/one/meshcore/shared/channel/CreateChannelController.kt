package one.meshcore.shared.channel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import one.meshcore.shared.contact.utf8BytePrefix
import one.meshcore.shared.crypto.secureRandomBytes
import one.meshcore.shared.model.Uuid

/**
 * The create-private-channel form state, two-phase like the iOS `CreatePrivateChannelView`. In the form
 * phase [createdChannel] is null and the user edits [name] over the pre-generated [secret]; once the write
 * acks it flips to the share phase carrying [createdChannel], and the UI shows the QR + manual-share.
 */
data class CreateChannelState(
    val name: String = "",
    val secret: ByteArray = ByteArray(ChannelSecret.SIZE_BYTES),
    val isCreating: Boolean = false,
    val createdChannel: ChannelDto? = null,
    val error: ChannelActionError? = null,
) {
    /** The form has flipped to the share phase. */
    val isCreated: Boolean get() = createdChannel != null

    /** Create is enabled with a non-blank name and no write in flight. */
    val canCreate: Boolean get() = name.isNotEmpty() && !isCreating
}

/**
 * Drives the create-private-channel flow (the Kotlin analogue of the iOS `CreatePrivateChannelView`): mints
 * a CSPRNG secret on construction, live-truncates the name to the firmware budget, writes the channel with
 * [ChannelService.setChannelWithSecret] to the first free slot, and flips to the share phase carrying the
 * created row. Plain over provider closures so it is unit-testable on the JVM.
 *
 * The three-axis identity invariant holds: `radioID` comes from the injected [Dependencies.radioId]
 * provider (`device.radioID`) and is never minted here.
 */
class CreateChannelController(
    private val dependencies: Dependencies,
    generateSecret: () -> ByteArray = { secureRandomBytes(ChannelSecret.SIZE_BYTES) },
) {
    /** Live, per-connection inputs read at create time; a null provider result means disconnected. */
    class Dependencies(
        val channelService: () -> ChannelService?,
        val radioId: () -> Uuid?,
        /** The free user slots computed by the hub; the channel targets the first, falling back to slot 1. */
        val availableSlots: () -> List<Int>,
    )

    private val _state = MutableStateFlow(CreateChannelState(secret = generateSecret()))
    val state: StateFlow<CreateChannelState> = _state.asStateFlow()

    /** Sets the name, truncated live to the firmware's usable name-byte budget. */
    fun setName(name: String) = _state.update { it.copy(name = name.utf8BytePrefix(MAX_USABLE_NAME_BYTES)) }

    /**
     * Writes the channel to the radio and, on success, flips to the share phase carrying the created row.
     * A missing device/service or a failed write lands in [CreateChannelState.error] and re-enables the form.
     */
    suspend fun create() {
        val current = _state.value
        if (!current.canCreate) return
        val service = dependencies.channelService()
        val radioId = dependencies.radioId()
        if (service == null || radioId == null) {
            _state.update { it.copy(error = channelUnavailableError(radioId)) }
            return
        }

        val slot = dependencies.availableSlots().firstOrNull() ?: ChannelSlots.USER_CHANNEL_MIN_SLOT
        _state.update { it.copy(isCreating = true, error = null) }

        val outcome = service.runChannelWrite(radioId, slot) {
            setChannelWithSecret(radioId, slot, current.name, current.secret)
        }
        when (outcome) {
            is ChannelWriteOutcome.Written ->
                _state.update { it.copy(isCreating = false, createdChannel = outcome.channel) }
            is ChannelWriteOutcome.Failed ->
                _state.update { it.copy(isCreating = false, error = outcome.error) }
        }
    }

    /** The `meshcore://channel/add` URI for the current name + secret, for the share-phase QR and copy. */
    fun shareUri(): String = ChannelUri.export(_state.value.name, _state.value.secret)
}
