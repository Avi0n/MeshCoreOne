package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid
import kotlin.test.assertTrue

/** Marks contact sync complete, then queues [key] as a pending advert for [radioID]. */
internal suspend fun queueSyncedAdvert(service: ContactService, radioID: Uuid, key: ByteArray) {
    service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }
    assertTrue(!service.handleAdvertisement(radioID, key))
}
