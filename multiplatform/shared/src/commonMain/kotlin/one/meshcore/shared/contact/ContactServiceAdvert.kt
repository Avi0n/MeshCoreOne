package one.meshcore.shared.contact

import kotlinx.coroutines.CoroutineScope

/** Advert-delta scope, the busy claim, and the foreground gate that parks background work. */
data class ContactServiceAdvert(
    val deltaScope: CoroutineScope? = null,
    val advertSyncClaim: AdvertContactSyncClaim = AdvertContactSyncClaim(),
    /**
     * Scene-phase gate for advert delta work. Defaults to foreground.
     * Background parks 0x80 / path / 0x8F until [ContactService.handleReturnToForeground] or teardown.
     */
    val isInForeground: suspend () -> Boolean = { true },
)
