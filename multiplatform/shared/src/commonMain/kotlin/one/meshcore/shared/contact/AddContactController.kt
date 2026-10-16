package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.decodeHex
import one.meshcore.shared.model.toLowercaseHex

private const val ASCII_HEX_DIGITS = "0123456789abcdefABCDEF"

/** iOS `AddContactSheet` live-filter: keep ASCII hex digits and lowercase them. */
fun sanitizePublicKeyHexInput(raw: String): String = raw.filter { it in ASCII_HEX_DIGITS }.lowercase()

/** An error surfaced by the Add-contact form. Resolved to a localized message in the UI layer. */
sealed interface AddContactError {
    /** No live radio to add against. */
    data object NotConnected : AddContactError

    /** The normalized hex could not be decoded to bytes. */
    data object InvalidFormat : AddContactError

    /** The decoded key was not exactly [ChatConstants.PUBLIC_KEY_SIZE_BYTES] bytes. */
    data object InvalidSize : AddContactError

    /** The device contact table is full; [maxContacts] is the device cap, or null when unknown. */
    data class NodeListFull(val maxContacts: Int?) : AddContactError

    /** Any other lower-layer failure, carrying its already-localized message. */
    data class ServiceMessage(val message: String) : AddContactError
}

/**
 * The Add-contact form state. [name], [publicKeyHex] and [type] are the editable fields; [pasteFailed]
 * flags an unparseable clipboard URL inline; [error] is the modal submit error; [isSubmitting] gates the
 * Add button and the whole form while the device round-trip is in flight.
 */
data class AddContactState(
    val name: String = "",
    val publicKeyHex: String = "",
    val type: ContactType = ContactType.CHAT,
    val isSubmitting: Boolean = false,
    val pasteFailed: Boolean = false,
    val error: AddContactError? = null,
) {
    /** Hex reduced to its normalized form: ASCII hex digits only, lowercased (the value actually decoded). */
    val normalizedPublicKeyHex: String
        get() = sanitizePublicKeyHexInput(publicKeyHex)

    /** True once the normalized key is a full 64-character (32-byte) hex string. */
    val isValidPublicKey: Boolean get() = normalizedPublicKeyHex.length == PUBLIC_KEY_HEX_LENGTH

    /** The Add action is enabled with a non-blank name, a full key, and no submit in flight. */
    val canAdd: Boolean get() = name.isNotEmpty() && isValidPublicKey && !isSubmitting
}

/**
 * Drives the manual Add-contact form (the Kotlin analogue of the iOS `AddContactSheet`): live name
 * byte-truncation and hex normalization, paste-a-URL prefill through the shared [ContactCardUri] parser,
 * and the submit path that builds the flood [importContactFrame] and calls
 * [ContactService.addOrUpdateContact]. Plain over provider closures so it is unit-testable on the JVM.
 *
 * The three-axis identity invariant holds: `radioID` comes from the injected [Dependencies.radioId]
 * provider (`device.radioID`) and is never minted here.
 */
class AddContactController(private val dependencies: Dependencies) {
    /** Live, per-connection inputs read at submit time; a null provider result means disconnected. */
    data class Dependencies(
        val contactService: () -> ContactService?,
        val radioId: () -> Uuid?,
        /**
         * Whether a radio session is live. Offline [ContactService] and last-connected [radioId] still
         * exist while disconnected; iOS `AddContactSheet` requires `appState.services`.
         */
        val isRadioConnected: () -> Boolean = { false },
        /** The device's contact-table cap for the "node list full" message, or null when unknown. */
        val maxContacts: () -> Int? = { null },
        /** Epoch-seconds clock stamped onto the new contact frame's `lastModified`. */
        val now: () -> Long,
        /** Localized text for every contact-service failure, resolved at the Compose boundary. */
        val errorText: (Throwable) -> String,
    )

    private val _state = MutableStateFlow(AddContactState())
    val state: StateFlow<AddContactState> = _state.asStateFlow()

    /** Sets the name, truncated live to the firmware's usable name-byte budget. */
    fun setName(name: String) = _state.update { it.copy(name = name.utf8BytePrefix(MAX_USABLE_NAME_BYTES)) }

    /** Live-filters the public-key field to lowercase ASCII hex, matching iOS `AddContactSheet`. */
    fun setPublicKeyHex(hex: String) = _state.update { it.copy(publicKeyHex = sanitizePublicKeyHexInput(hex)) }

    fun setType(type: ContactType) = _state.update { it.copy(type = type) }

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Parses [clipboardText] as a `meshcore://contact/add` URI and prefills name/key/type on success,
     * clearing any prior error; on failure raises the inline [AddContactState.pasteFailed] flag. Mirrors
     * the iOS Paste-URL section.
     */
    fun pasteUrl(clipboardText: String?) {
        val card = clipboardText?.let { ContactCardUri.parse(it) }
        if (card == null) {
            _state.update { it.copy(pasteFailed = true) }
            return
        }
        _state.update {
            it.copy(
                name = card.name.utf8BytePrefix(MAX_USABLE_NAME_BYTES),
                publicKeyHex = card.publicKey.toLowercaseHex(),
                type = card.contactType,
                pasteFailed = false,
                error = null,
            )
        }
    }

    /**
     * Validates the key and adds the contact to the device and local store, then invokes [onAdded] with
     * the saved row id (or null if the lookup misses) so a URL add can open contact detail. Errors land
     * in [AddContactState.error]; the form re-enables on any error. Mirrors `AddContactSheet.handleAdd`.
     */
    suspend fun add(onAdded: (Uuid?) -> Unit) {
        val connected = connectedAdd() ?: return
        val publicKey = decodedPublicKey(connected.current) ?: return
        submitAdd(connected, publicKey, onAdded)
    }

    private fun connectedAdd(): ConnectedAdd? {
        val current = _state.value
        val service = dependencies.contactService()
        val radioId = dependencies.radioId()
        if (service == null || radioId == null || !dependencies.isRadioConnected()) {
            _state.update { it.copy(error = AddContactError.NotConnected) }
            return null
        }
        return ConnectedAdd(current, service, radioId)
    }

    private fun decodedPublicKey(current: AddContactState): ByteArray? {
        val publicKey = decodeHex(current.normalizedPublicKeyHex) ?: return rejectedKey(AddContactError.InvalidFormat)
        if (publicKey.size != ChatConstants.PUBLIC_KEY_SIZE_BYTES) return rejectedKey(AddContactError.InvalidSize)
        return publicKey
    }

    private fun rejectedKey(error: AddContactError): ByteArray? {
        _state.update { it.copy(error = error) }
        return null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun submitAdd(connected: ConnectedAdd, publicKey: ByteArray, onAdded: (Uuid?) -> Unit) {
        _state.update { it.copy(isSubmitting = true, error = null) }
        val frame = importContactFrame(
            name = connected.current.name,
            publicKey = publicKey,
            type = connected.current.type,
            nowSeconds = dependencies.now(),
        )
        // ContactTableFull carries no field the form reads; the type is the signal. Every other
        // failure, including a transport throwable, must clear isSubmitting or the form stays disabled.
        try {
            connected.service.addOrUpdateContact(radioID = connected.radioId, frame = frame)
            // iOS fetches the new row and opens contact detail from a URL add.
            onAdded(connected.service.getContact(connected.radioId, publicKey)?.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            when (error) {
                is ContactServiceError.ContactTableFull -> _state.update {
                    it.copy(
                        error = AddContactError.NodeListFull(dependencies.maxContacts()),
                        isSubmitting = false,
                    )
                }
                else -> _state.update {
                    it.copy(
                        error = AddContactError.ServiceMessage(
                            dependencies.errorText(error),
                        ),
                        isSubmitting = false,
                    )
                }
            }
        }
    }

    private class ConnectedAdd(val current: AddContactState, val service: ContactService, val radioId: Uuid)
}
