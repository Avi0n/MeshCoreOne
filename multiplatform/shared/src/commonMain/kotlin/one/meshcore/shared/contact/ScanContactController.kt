package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runExceptionFallback

/** An error surfaced by the QR scanner overlay. Resolved to a localized message in the UI layer. */
sealed interface ScanContactError {
    /** The decoded string was not a valid `meshcore://contact/add` URI; the scanner keeps scanning. */
    data object InvalidFormat : ScanContactError

    /** No live radio to import against. */
    data object NotConnected : ScanContactError

    /** The device contact table is full; [maxContacts] is the device cap, or null when unknown. */
    data class NodeListFull(val maxContacts: Int?) : ScanContactError

    /** Any other import failure, carrying its already-localized message. */
    data class ImportFailed(val message: String) : ScanContactError
}

/**
 * The QR-scanner state. A valid code resolves to [scannedContact] for review before import.
 * The ticks let the UI distinguish repeated selection, failure, and add-success feedback.
 */
data class ScanContactState(
    val scannedContact: ContactCard? = null,
    val existingContact: ContactDto? = null,
    val isResolvingScan: Boolean = false,
    val isImporting: Boolean = false,
    val permissionDenied: Boolean = false,
    val error: ScanContactError? = null,
    val selectionTick: Int = 0,
    val errorTick: Int = 0,
    val successTick: Int = 0,
)

/**
 * Drives the contact-QR scan → parse → import flow (the Kotlin analogue of the iOS `ScanContactQRView`
 * state machine), independent of any camera so it is unit-testable: a decoded string is fed to [onDecoded]
 * exactly as a real `CameraX` + barcode callback would feed it. A valid `meshcore://contact/add` URI is
 * parsed via the shared [ContactCardUri] parser, then imported through [ContactService.addOrUpdateContact].
 *
 * The three-axis identity invariant holds: `radioID` comes from the injected [Dependencies.radioId]
 * provider (`device.radioID`) and is never minted here.
 */
class ScanContactController(private val scope: CoroutineScope, private val dependencies: Dependencies) {
    /** Live, per-connection inputs read at import time; a null provider result means disconnected. */
    data class Dependencies(
        val contactService: () -> ContactService?,
        val radioId: () -> Uuid?,
        /**
         * Whether a radio session is live. Offline [ContactService] and last-connected [radioId] still
         * exist while disconnected; iOS `ScanContactQRView` requires `appState.services`.
         */
        val isRadioConnected: () -> Boolean = { false },
        /** The device's contact-table cap for the "node list full" message, or null when unknown. */
        val maxContacts: () -> Int? = { null },
        /** Epoch-seconds clock stamped onto the imported contact frame's `lastModified`. */
        val now: () -> Long,
        /** Invoked with the saved row after Add or View, for dismiss/navigation to Contact Detail. */
        val onScanned: (ContactDto) -> Unit = {},
        /** Localized text for every contact-service failure, resolved at the Compose boundary. */
        val errorText: (Throwable) -> String,
        /** Current iOS `contacts.common.errorOccurred`; hosts inject the Compose resource. */
        val unmappedError: () -> String,
    )

    private val _state = MutableStateFlow(ScanContactState())
    val state: StateFlow<ScanContactState> = _state.asStateFlow()
    private var resolveJob: Job? = null
    private var importJob: Job? = null
    private var isClosed = false

    /** Reports a camera-permission denial; the UI shows the settings prompt. */
    fun onPermissionDenied() = _state.update { it.copy(permissionDenied = true) }

    /** Dismisses the current error caption, returning to the plain scanning instruction. */
    fun clearError() = _state.update { it.copy(error = null) }

    /** Parses and resolves a QR for review. No radio import runs until [onPrimaryAction]. */
    fun onDecoded(decoded: String) {
        if (isClosed) return
        val state = _state.value
        if (state.isImporting || state.isResolvingScan || state.scannedContact != null) {
            return
        }

        val card = ContactCardUri.parse(decoded)
        if (card == null) {
            _state.update { it.copy(error = ScanContactError.InvalidFormat, errorTick = it.errorTick + 1) }
            return
        }

        // Claim the scan before lookup so duplicate camera callbacks cannot replace the review.
        _state.update { it.copy(isResolvingScan = true, error = null) }
        resolveJob = scope.launch { resolve(card) }
    }

    private suspend fun resolve(card: ContactCard) {
        val service = dependencies.contactService()
        val radioId = dependencies.radioId()
        // The review still shows the scanned identity if local lookup is unavailable.
        val existing = runExceptionFallback({ null }) {
            if (service != null && radioId != null) service.getContact(radioId, card.publicKey) else null
        }
        if (isClosed) return
        _state.update {
            it.copy(
                scannedContact = card,
                existingContact = existing,
                isResolvingScan = false,
                selectionTick = it.selectionTick + 1,
            )
        }
    }

    /** Adds the reviewed identity, or opens an already-saved contact without another radio import. */
    fun onPrimaryAction() {
        if (isClosed) return
        val state = _state.value
        val card = state.scannedContact
        if (card == null || state.isImporting || state.isResolvingScan) return
        val existing = state.existingContact
        if (existing != null) {
            _state.update { it.copy(isImporting = true) }
            dependencies.onScanned(existing)
        } else {
            _state.update { it.copy(isImporting = true, error = null) }
            importJob = scope.launch { import(card) }
        }
    }

    /** Returns to the live scanner without importing the reviewed identity. */
    fun resetToScanner() {
        if (isClosed || _state.value.isImporting) return
        _state.update {
            it.copy(scannedContact = null, existingContact = null, isResolvingScan = false, error = null)
        }
    }

    /** Cancels lookup/import work owned by this scanner instance. */
    fun close() {
        if (isClosed) return
        isClosed = true
        resolveJob?.cancel()
        resolveJob = null
        importJob?.cancel()
        importJob = null
    }

    // ContactTableFull maps to a typed scan error. Every other failure stays on the scanner so a
    // transport or bridge throwable cannot strand isImporting and ignore later codes.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun import(card: ContactCard) {
        val service = dependencies.contactService()
        val radioId = dependencies.radioId()
        if (service == null || radioId == null || !dependencies.isRadioConnected()) {
            _state.update {
                it.copy(error = ScanContactError.NotConnected, isImporting = false, errorTick = it.errorTick + 1)
            }
            return
        }

        val frame = importContactFrame(
            name = card.name,
            publicKey = card.publicKey,
            type = card.contactType,
            nowSeconds = dependencies.now(),
        )
        try {
            service.addOrUpdateContact(radioID = radioId, frame = frame)
            val added = service.getContact(radioId, card.publicKey)
            if (isClosed) return
            if (added == null) {
                _state.update {
                    it.copy(
                        error = ScanContactError.ImportFailed(dependencies.unmappedError()),
                        isImporting = false,
                        errorTick = it.errorTick + 1,
                    )
                }
            } else {
                _state.update { it.copy(isImporting = false, successTick = it.successTick + 1) }
                dependencies.onScanned(added)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ContactServiceError.ContactTableFull) {
            _state.update {
                it.copy(
                    error = ScanContactError.NodeListFull(dependencies.maxContacts()),
                    isImporting = false,
                    errorTick = it.errorTick + 1,
                )
            }
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    error = ScanContactError.ImportFailed(
                        dependencies.errorText(error),
                    ),
                    isImporting = false,
                    errorTick = it.errorTick + 1,
                )
            }
        }
    }
}
