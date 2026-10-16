package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChatShareLogic
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationAuthorizationStatus
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.LocationServiceError
import one.meshcore.shared.map.isAuthorized
import one.meshcore.shared.model.Uuid
import kotlin.time.Duration.Companion.seconds

/**
 * Leading "+" share control for [ChatInputBar]: dropdown actions plus the contact-picker sheet.
 * Kept out of [ChatThreadScreen] so the thread scaffold stays under the complexity gate.
 */
@Composable
fun ChatShareLeading(
    onInsertShared: (String) -> Unit,
    selfPublicKey: () -> ByteArray?,
    localNodeName: () -> String?,
    nodeCoordinate: () -> LatLng?,
    locationService: () -> LocationService?,
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    hashSize: Int = 1,
) {
    val scope = rememberCoroutineScope()
    var showShareContactPicker by remember { mutableStateOf(false) }
    val location = locationService()
    val phoneCoordinate by (location?.currentLocation ?: idlePhoneLocation).collectAsStateWithLifecycle()
    val authorization by (location?.authorizationStatus ?: idleAuthorization).collectAsStateWithLifecycle()
    val nodeCoord = nodeCoordinate()
    val canShareLocation = ChatShareLogic.canShareLocation(
        phoneCoordinate = phoneCoordinate,
        nodeCoordinate = nodeCoord,
        locationAuthorized = authorization.isAuthorized,
    )
    val canShareMyInfo = ChatShareLogic.canShareMyInfo(selfPublicKey(), localNodeName())

    ChatShareMenu(
        canShareLocation = canShareLocation,
        canShareMyInfo = canShareMyInfo,
        onShareLocation = {
            scope.launch {
                val loc = locationService()
                val fresh = if (loc != null && loc.isAuthorized) {
                    try {
                        loc.requestCurrentLocation(SHARE_LOCATION_TIMEOUT)
                    } catch (_: LocationServiceError) {
                        null
                    }
                } else {
                    null
                }
                val coord = ChatShareLogic.resolveShareCoordinate(
                    fresh = fresh,
                    phone = loc?.currentLocation?.value,
                    node = nodeCoordinate(),
                ) ?: return@launch
                onInsertShared(
                    ChatShareLogic.formatShareCoordinate(coord.latitude, coord.longitude),
                )
            }
        },
        onShareContact = { showShareContactPicker = true },
        onShareMyInfo = {
            val key = selfPublicKey()
            val name = localNodeName()
            if (ChatShareLogic.canShareMyInfo(key, name) && key != null && name != null) {
                onInsertShared(ChatShareLogic.formatMyInfoToken(key, name))
            }
        },
    )

    if (showShareContactPicker) {
        val currentContactService = contactService()
        val currentRadioId = radioId()
        ShareContactPickerSheet(
            loadContacts = { loadShareContacts(contactService, radioId) },
            serviceOwner = currentContactService,
            radioId = currentRadioId,
            onInsert = onInsertShared,
            onDismiss = { showShareContactPicker = false },
            hashSize = hashSize,
        )
    }
}

private suspend fun loadShareContacts(contactService: () -> ContactService?, radioId: () -> Uuid?): List<ContactDto> {
    val service = contactService()
    val radio = radioId()
    return if (service == null || radio == null) emptyList() else service.getContacts(radio)
}

/** On-tap location fix timeout for Share Location (shorter than the service default). */
private val SHARE_LOCATION_TIMEOUT = 5.seconds

private val idlePhoneLocation = MutableStateFlow<LatLng?>(null)
private val idleAuthorization = MutableStateFlow(LocationAuthorizationStatus.NOT_DETERMINED)
