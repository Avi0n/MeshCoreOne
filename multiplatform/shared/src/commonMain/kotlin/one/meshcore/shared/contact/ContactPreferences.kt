package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

private const val FAVORITE_FLAG: Int = 0x01

/** Mask to keep every non-favorite flag bit while a favorite toggle rewrites bit 0. */
private const val NON_FAVORITE_FLAGS: Int = FAVORITE_FLAG.inv()

/** Contact preference writes, separated from advert and path synchronization state. */
internal class ContactPreferences(
    private val session: ContactSession,
    private val store: ContactStore,
    private val syncCoordinator: ContactSyncCoordinator,
    private val cleanupCoordinator: ContactCleanupHandling,
) {
    suspend fun updateContactPreferences(
        contactID: Uuid,
        nickname: String? = null,
        isBlocked: Boolean? = null,
        isFavorite: Boolean? = null,
    ) {
        val existing = store.updateContactFields(contactID) { current ->
            val resolvedNickname = if (nickname != null) {
                nickname.trim().ifEmpty { null }
            } else {
                current.nickname
            }
            current.copy(
                nickname = resolvedNickname,
                isBlocked = isBlocked ?: current.isBlocked,
                isFavorite = isFavorite ?: current.isFavorite,
                unreadCount = if (isBlocked == true && !current.isBlocked) 0 else current.unreadCount,
            )
        } ?: throw ContactServiceError.ContactNotFound()

        val isBeingBlocked = isBlocked == true && !existing.isBlocked
        val isBeingUnblocked = isBlocked == false && existing.isBlocked

        when {
            isBeingBlocked -> cleanupCoordinator.handleCleanup(
                contactID,
                ContactCleanupReason.BLOCKED,
                existing.publicKey,
            )
            isBeingUnblocked -> cleanupCoordinator.handleCleanup(
                contactID,
                ContactCleanupReason.UNBLOCKED,
                existing.publicKey,
            )
        }

        syncCoordinator.notifyContactsChanged()
    }

    suspend fun updateContactOCVSettings(
        contactID: Uuid,
        preset: String,
        customArray: String?,
        isCurrent: () -> Boolean = { true },
    ) {
        val context = currentCoroutineContext()
        fun checkOwner() {
            context.ensureActive()
            if (!isCurrent()) throw CancellationException("Battery curve owner was replaced")
        }
        checkOwner()
        // The provider reads caller-owned state; this row is only an existence check.
        val existing = store.fetchContact(contactID)
        checkOwner()
        if (existing == null) throw ContactServiceError.ContactNotFound()
        val previous = store.updateContactFields(contactID) { current ->
            context.ensureActive()
            current.copy(ocvPreset = preset, customOCVArrayString = customArray)
        }
        if (previous == null) {
            checkOwner()
            throw ContactServiceError.ContactNotFound()
        }
    }

    suspend fun updateContactAvatar(contactID: Uuid, imageData: ByteArray?) {
        store.updateContactFields(contactID) { current -> current.copy(avatarImageData = imageData) }
            ?: throw ContactServiceError.ContactNotFound()
        syncCoordinator.notifyContactsChanged()
    }

    suspend fun setContactFavorite(contactID: Uuid, isFavorite: Boolean) {
        val existing = store.fetchContact(contactID) ?: throw ContactServiceError.ContactNotFound()
        val newFlags = if (isFavorite) existing.flags or FAVORITE_FLAG else existing.flags and NON_FAVORITE_FLAGS
        changeContactFlags(existing, newFlags)
        // Capture the confirmed device flags even if the caller leaves the screen.
        withContext(NonCancellable) {
            store.updateContactFields(contactID) { current -> current.copy(flags = newFlags, isFavorite = isFavorite) }
                ?: throw ContactServiceError.ContactNotFound()
        }
    }

    suspend fun setTelemetryPermissions(contactID: Uuid, granted: Boolean) {
        val existing = store.fetchContact(contactID) ?: throw ContactServiceError.ContactNotFound()
        val newFlags = if (granted) existing.flags or TELEMETRY_FLAGS else existing.flags and TELEMETRY_FLAGS.inv()
        changeContactFlags(existing, newFlags)
        // Capture the confirmed device flags even if the caller leaves the screen.
        withContext(NonCancellable) {
            store.updateContactFields(contactID) { current -> current.copy(flags = newFlags) }
                ?: throw ContactServiceError.ContactNotFound()
        }
    }

    private suspend fun changeContactFlags(contact: ContactDto, flags: Int) {
        runContactSession(mapDeviceCode = null) {
            session.changeContactFlags(contact.toMeshContact(), flags)
        }
    }
}
