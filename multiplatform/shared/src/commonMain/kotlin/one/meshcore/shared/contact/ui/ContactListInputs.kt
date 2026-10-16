package one.meshcore.shared.contact.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.PreferencesStore

/**
 * Values the Nodes tab reads. The contact UI does not take the shell bundle.
 */
internal data class ContactListInputs(
    val contactService: () -> ContactService?,
    val radioId: () -> Uuid?,
    val connectionState: StateFlow<DeviceConnectionState>,
    /**
     * The connected radio's key-prefix width in bytes, sizing the monospaced id prefix at the head of every
     * row. On a 2- or 3-byte mesh a 1-byte prefix renders two different nodes identically, so this has no
     * default: a caller that omits it loses the distinction silently.
     */
    val deviceHashSize: () -> Int?,
    val onContactTap: (contactId: String) -> Unit,
    val onContactRemoved: (ContactDto) -> Unit,
    val selectedContactId: String? = null,
    val onSendMessage: (contactId: String) -> Unit = {},
    val onAddContact: () -> Unit = {},
    val onShareMyContact: () -> Unit = {},
    val discoveredNodeStore: () -> DiscoveredNodeStore? = { null },
    /** The user's location for Distance sort; `null` when unavailable. */
    val userLocation: () -> UserLocation? = { null },
    /** The connected radio's own public key, used to hide the ZephCore V-contact's Delete affordance. */
    val selfPublicKey: () -> ByteArray? = { null },
    /** Persists the sort order across launches; `null` disables it. */
    val sortOrderStore: PreferencesStore? = null,
    /** Drives the Distance-sort location gate and denied alert; `null` disables the gate. */
    val locationService: LocationService? = null,
    val onOpenAppSettings: () -> Unit = {},
    /** When true, show Discovery (owned by the navigation coordinator, not local state). */
    val showingDiscovery: Boolean = false,
    /** When true, show Blocked Contacts (owned by the coordinator). */
    val showingBlocked: Boolean = false,
    val onDiscover: () -> Unit = {},
    val onBlockedContacts: () -> Unit = {},
    val onBackFromDiscovery: () -> Unit = {},
    val onBackFromBlocked: () -> Unit = {},
    /** Emits when an app-side contact mutation changed the table. */
    val listInvalidations: Flow<Unit> = emptyFlow(),
    /** The device's contact-table cap, interpolated into Discover's "node list is full" error. */
    val maxContacts: () -> Int? = { null },
)
