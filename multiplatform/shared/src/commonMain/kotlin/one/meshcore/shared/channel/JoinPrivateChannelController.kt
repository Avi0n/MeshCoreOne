package one.meshcore.shared.channel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import one.meshcore.shared.contact.utf8BytePrefix
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.decodeHex

private const val HEX_RADIX = 16

/**
 * The join-private-channel form state: a name plus a hex secret. [secretHex] is stored already filtered to
 * uppercase hex digits (the live-format the field applies), so validity is a length check against the
 * expected 32 chars (16 bytes). [footerIsError] drives the inline "invalid secret" hint the iOS footer shows.
 */
data class JoinPrivateChannelState(
    val name: String = "",
    val secretHex: String = "",
    val isJoining: Boolean = false,
    val error: ChannelActionError? = null,
) {
    /** The cleaned secret is exactly 16 bytes of hex. */
    val isValidSecret: Boolean get() = secretHex.length == ChannelSecret.SIZE_BYTES * 2

    /** Join is enabled with a non-blank name, a full valid secret, and no write in flight. */
    val canJoin: Boolean get() = name.isNotEmpty() && isValidSecret && !isJoining

    /** Show the "invalid secret" footer while the user has typed something that is not yet a full secret. */
    val footerIsError: Boolean get() = secretHex.isNotEmpty() && !isValidSecret
}

/**
 * Drives the join-private-channel flow (the Kotlin analogue of the iOS `JoinPrivateChannelView`): live name
 * truncation, live hex filtering + uppercasing of the secret field, and a join that decodes the hex to 16
 * bytes and calls [ChannelService.setChannelWithSecret] on the first free slot. Best-effort read-back like
 * the iOS flow: a nil result still completes with a null channel rather than erroring.
 */
class JoinPrivateChannelController(private val dependencies: Dependencies) {
    /** Live, per-connection inputs read at join time; a null provider result means disconnected. */
    class Dependencies(
        val channelService: () -> ChannelService?,
        val radioId: () -> Uuid?,
        val availableSlots: () -> List<Int>,
    )

    private val _state = MutableStateFlow(JoinPrivateChannelState())
    val state: StateFlow<JoinPrivateChannelState> = _state.asStateFlow()

    /** Sets the name, truncated live to the firmware's usable name-byte budget. */
    fun setName(name: String) = _state.update { it.copy(name = name.utf8BytePrefix(MAX_USABLE_NAME_BYTES)) }

    /** Sets the secret field, filtered live to uppercase hex digits (mirrors the iOS `.onChange` filter). */
    fun setSecretHex(hex: String) = _state.update {
        it.copy(secretHex = hex.filter { c -> c.digitToIntOrNull(HEX_RADIX) != null }.uppercase())
    }

    /**
     * Decodes the hex secret and joins the channel on the first free slot, invoking [onJoined] with the
     * resulting row (possibly null on a best-effort read-back miss). Errors land in the form state.
     */
    suspend fun join(onJoined: (ChannelDto?) -> Unit) {
        val current = _state.value
        if (!current.canJoin) return
        val inputs = privateJoinInputs(current) ?: return

        val slot = current.availableSlot()
        _state.update { it.copy(isJoining = true, error = null) }
        val outcome = inputs.service.runChannelWrite(inputs.radioId, slot) {
            setChannelWithSecret(inputs.radioId, slot, current.name, inputs.secret)
        }
        when (outcome) {
            is ChannelWriteOutcome.Written -> {
                _state.update { it.copy(isJoining = false) }
                onJoined(outcome.channel)
            }
            is ChannelWriteOutcome.Failed -> _state.update { it.copy(isJoining = false, error = outcome.error) }
        }
    }

    private fun JoinPrivateChannelState.availableSlot(): Int =
        dependencies.availableSlots().firstOrNull() ?: ChannelSlots.USER_CHANNEL_MIN_SLOT

    private fun privateJoinInputs(current: JoinPrivateChannelState): PrivateJoinInputs? {
        val service = dependencies.channelService()
        val radioId = dependencies.radioId()
        val secret = decodeHex(current.secretHex)
        if (service != null && radioId != null && secret != null) {
            if (ChannelSecret.validateSecret(secret)) return PrivateJoinInputs(service, radioId, secret)
        }
        val error = when {
            service == null || radioId == null -> channelUnavailableError(radioId)
            else -> ChannelActionError.InvalidSecretFormat
        }
        _state.update { it.copy(error = error) }
        return null
    }

    private class PrivateJoinInputs(val service: ChannelService, val radioId: Uuid, val secret: ByteArray)
}
