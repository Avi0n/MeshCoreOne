package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto

/** Observable state for the share-contact picker sheet. */
data class ShareContactPickerState(
    val contacts: List<ContactDto> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

/**
 * Loads shareable contacts for the composer share picker. A persist fault must not leave the
 * spinner up: iOS `ShareContactPickerSheet` clears loading in `defer` and sets `errorMessage`.
 */
class ShareContactPickerViewModel(
    private val loadFailedMessage: () -> String,
    private val onLoadError: (Throwable) -> Unit = {},
    private val loadContacts: suspend () -> List<ContactDto>,
) {
    private val _state = MutableStateFlow(ShareContactPickerState())

    val state: StateFlow<ShareContactPickerState> = _state.asStateFlow()

    @Suppress("TooGenericExceptionCaught")
    suspend fun load() {
        _state.update { it.copy(contacts = emptyList(), isLoading = true, errorMessage = null) }
        try {
            val contacts = ChatShareLogic.shareableContacts(loadContacts())
            _state.update { it.copy(contacts = contacts, isLoading = false, errorMessage = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onLoadError(error)
            _state.update {
                it.copy(contacts = emptyList(), isLoading = false, errorMessage = loadFailedMessage())
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
