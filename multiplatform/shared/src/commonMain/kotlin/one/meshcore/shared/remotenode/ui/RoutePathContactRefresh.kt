package one.meshcore.shared.remotenode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.collectContactUiReloads
import one.meshcore.shared.contact.getContact
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runExceptionFallback

/**
 * Replaces [current] only when [fetch] returns a row. A miss or persist fault keeps the sheet's
 * seed contact so a transient store read cannot blank the route section or kill later
 * invalidations. iOS `refreshRouteContact` uses `try? fetchContact`.
 */
suspend fun refreshRoutePathContact(
    current: ContactDto,
    radioID: Uuid,
    publicKey: ByteArray,
    fetch: suspend (Uuid, ByteArray) -> ContactDto?,
): ContactDto = runExceptionFallback({ current }) {
    fetch(radioID, publicKey) ?: current
}

/**
 * Live route-path contact for a repeater sheet. Reloads on open and on each [invalidations]
 * pulse so a flood login that writes a learned path can redraw the route section.
 */
@Composable
internal fun rememberLiveRoutePathContact(
    seed: ContactDto,
    session: RemoteNodeSessionDto,
    contactService: () -> ContactService?,
    invalidations: Flow<Unit> = emptyFlow(),
    enabled: Boolean = true,
): ContactDto {
    val publicKeyHash = session.publicKey.contentHashCode()
    val currentContactService = contactService()
    var live by remember(
        session.id,
        session.radioID,
        publicKeyHash,
        seed.id,
        seed.lastModified,
        currentContactService,
    ) {
        mutableStateOf(seed)
    }
    LaunchedEffect(
        enabled,
        session.id,
        session.radioID,
        publicKeyHash,
        seed.id,
        seed.lastModified,
        currentContactService,
    ) {
        if (!enabled) return@LaunchedEffect
        live = seed
        suspend fun reload() {
            live = refreshRoutePathContact(
                current = live,
                radioID = session.radioID,
                publicKey = session.publicKey,
            ) { radio, key -> currentContactService?.getContact(radio, key) }
        }
        reload()
        // iOS RepeaterStatusView refreshRouteContact on contactsVersion (0x80 path stamp).
        collectContactUiReloads(currentContactService, invalidations) { reload() }
    }
    return live
}
