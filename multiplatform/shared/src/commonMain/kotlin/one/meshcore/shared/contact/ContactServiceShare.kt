package one.meshcore.shared.contact

/**
 * Zero-hop broadcast of a contact's advertisement. A device `TABLE_FULL` becomes
 * [ContactServiceError.ShareContactUnavailable]; `NOT_FOUND` becomes
 * [ContactServiceError.ContactNotFound]. Mirrors `shareContact(publicKey:)`.
 */
suspend fun ContactService.shareContact(publicKey: ByteArray) = contactRadioCommands.shareContact(publicKey)
