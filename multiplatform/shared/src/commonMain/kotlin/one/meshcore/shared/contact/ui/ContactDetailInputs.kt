package one.meshcore.shared.contact.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.PathManagementViewModel

/**
 * What the shell (or a test harness) hands the contact detail screen.
 * Optional actions default to absent so a harness can open the screen with only navigation.
 */
internal data class ContactDetailInputs(
    val contactId: String,
    val contactService: () -> ContactService?,
    val radioId: () -> Uuid?,
    val onBack: () -> Unit,
    val onSendMessage: (ContactDto) -> Unit,
    val onEditPath: (ContactDto) -> Unit,
    val onShare: (ContactDto) -> Unit,
    val onZeroHopPing: (suspend (ContactDto) -> ContactPingResult)? = null,
    val onShowTelemetry: ((ContactDto) -> Unit)? = null,
    val onShowSavedHistory: ((ContactDto) -> Unit)? = null,
    val onJoinRoom: ((ContactDto) -> Unit)? = null,
    val onManagement: ((ContactDto) -> Unit)? = null,
    val onOpenInMaps: ((ContactDto) -> Unit)? = null,
    val onDeleted: () -> Unit = onBack,
    /** After a successful clear, leave detail; defaults to [onBack]. */
    val onMessagesCleared: () -> Unit = onBack,
    /** Builds the path view model that drives live discovery; `null` keeps send-and-reload. */
    val buildPathViewModel: ((CoroutineScope) -> PathManagementViewModel)? = null,
    /** The connected radio's own public key, used to spot the ZephCore V-contact; `null` shows delete. */
    val selfPublicKey: () -> ByteArray? = { null },
    /**
     * Whether the radio is connected and synced. Gates every action that reaches it, so a mid-screen
     * disconnect cannot leave a command tappable.
     */
    val isRadioReady: Boolean = true,
    /**
     * Whether a radio session is live (Connected/Syncing/Ready). Offline [ContactService] still exists
     * for reloads; writes must not run against that disconnected stand-in.
     */
    val isRadioConnected: Boolean = isRadioReady,
    val isOffline: Boolean = false,
    /** True when presented from a DM Info control; hides Send Message. */
    val showFromDirectChat: Boolean = false,
    val listInvalidations: Flow<Unit>? = null,
)
