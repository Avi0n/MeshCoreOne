package one.meshcore.shared.contact.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationAuthorizationStatus

/** Resolved presentation values for [ContactListScreen]; live providers stay in [ContactListInputs]. */
data class ContactListScreenState(
    val isReady: Boolean,
    val nowSeconds: Long,
    /** The connected radio's key-prefix width in bytes (iOS `connectedDevice?.hashSize ?? 1`). */
    val hashSize: Int,
    val selectedContactId: String? = null,
    val userLocation: UserLocation? = null,
    /** The connected radio's own public key, used to hide Delete for the ZephCore V-contact. */
    val selfPublicKey: ByteArray? = null,
    /** Location authorization for the Distance-sort gate; `null` when no location service is wired. */
    val locationStatus: LocationAuthorizationStatus? = null,
)

/** Navigation and platform actions supplied to [ContactListScreen] by its composition root. */
data class ContactListScreenActions(
    val onContactTap: (ContactDto) -> Unit,
    val onSendMessage: (ContactDto) -> Unit = {},
    val onAddContact: () -> Unit = {},
    val onShareMyContact: () -> Unit = {},
    val onBlockedContacts: () -> Unit = {},
    val onDiscover: () -> Unit = {},
    val onRequestLocation: () -> Unit = {},
    val onRequestLocationPermission: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
)
