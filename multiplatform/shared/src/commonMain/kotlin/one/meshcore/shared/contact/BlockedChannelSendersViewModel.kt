package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.BlockedChannelSenderDto
import one.meshcore.shared.model.Uuid

/** The observable state of the Blocked Channel Senders screen. */
data class BlockedChannelSendersState(
    val senders: List<BlockedChannelSenderDto> = emptyList(),
    val isLoading: Boolean = false,
    /** False until the first load finishes so the spinner does not yield to an empty flash. */
    val hasLoadedOnce: Boolean = false,
)

/**
 * Drives Settings → Blocking → Channel Senders. A persist fault must not leave the spinner up: iOS
 * `BlockedChannelSendersView` clears loading in `defer` and lands an empty list.
 */
class BlockedChannelSendersViewModel(private val dependencies: Dependencies) {
    class Dependencies(val service: () -> BlockedChannelSenderService?, val radioId: () -> Uuid?)

    private val _state = MutableStateFlow(BlockedChannelSendersState())

    val state: StateFlow<BlockedChannelSendersState> = _state.asStateFlow()

    /** Loads blocked senders. Disconnected or a persist fault lands an empty list, never a stuck spinner. */
    suspend fun load() {
        val service = dependencies.service()
        val radioID = dependencies.radioId()
        if (service == null || radioID == null) {
            _state.update {
                it.copy(senders = emptyList(), isLoading = false, hasLoadedOnce = true)
            }
            return
        }
        if (!_state.value.hasLoadedOnce) {
            _state.update { it.copy(isLoading = true) }
        }
        try {
            val senders = service.blockedSenders(radioID)
            _state.update {
                it.copy(senders = senders, isLoading = false, hasLoadedOnce = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.update {
                it.copy(senders = emptyList(), isLoading = false, hasLoadedOnce = true)
            }
        }
    }

    /**
     * Unblocks [name] then reloads. A persist fault on delete is swallowed so the screen stays usable,
     * matching iOS which logs and still refreshes the blocked-name cache.
     */
    suspend fun unblock(name: String) {
        val service = dependencies.service()
        val radioID = dependencies.radioId()
        if (service == null || radioID == null) return
        try {
            service.unblockSender(radioID, name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // iOS logs and continues to the cache refresh.
        }
        load()
    }
}
