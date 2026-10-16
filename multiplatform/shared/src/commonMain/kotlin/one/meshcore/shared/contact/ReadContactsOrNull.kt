package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runExceptionFallback

/**
 * One contact-list read. Cancellation is rethrown. An error still propagates. Every other
 * failure returns null so the caller keeps its own empty-list or spinner policy.
 */
internal suspend fun ContactService.readContactsOrNull(radioID: Uuid): List<ContactDto>? =
    runExceptionFallback({ null }) { getContacts(radioID) }
