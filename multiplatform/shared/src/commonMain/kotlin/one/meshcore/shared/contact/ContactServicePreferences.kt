package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/**
 * Updates local preferences. [nickname] null leaves it unchanged; empty/whitespace clears it; else it
 * is trimmed and set. Blocking zeroes the unread count and runs the blocked cleanup; unblocking runs
 * the unblocked cleanup. Mirrors `updateContactPreferences`.
 */
suspend fun ContactService.updateContactPreferences(
    contactID: Uuid,
    nickname: String? = null,
    isBlocked: Boolean? = null,
    isFavorite: Boolean? = null,
) = contactPreferences.updateContactPreferences(contactID, nickname, isBlocked, isFavorite)

/** Updates the contact's local-only OCV battery-curve settings. Mirrors `updateContactOCVSettings`. */
suspend fun ContactService.updateContactOCVSettings(
    contactID: Uuid,
    preset: String,
    customArray: String?,
    isCurrent: () -> Boolean = { true },
) = contactPreferences.updateContactOCVSettings(contactID, preset, customArray, isCurrent)

/** Sets or clears a contact's locally stored profile picture (compressed JPEG). Mirrors `updateContactAvatar`. */
suspend fun ContactService.updateContactAvatar(contactID: Uuid, imageData: ByteArray?) =
    contactPreferences.updateContactAvatar(contactID, imageData)

/**
 * Sets a contact's favorite bit (flags bit 0) on the device, waits for the ack, then saves the local
 * row with the new flags and favorite bool. Favorite is device-synced, not app-only. Mirrors
 * `setContactFavorite`.
 */
suspend fun ContactService.setContactFavorite(contactID: Uuid, isFavorite: Boolean) =
    contactPreferences.setContactFavorite(contactID, isFavorite)

/**
 * Sets a contact's telemetry-permission bits (bits 1-3) on the device, preserving the favorite bit,
 * then saves the local row. Mirrors `setTelemetryPermissions`.
 */
suspend fun ContactService.setTelemetryPermissions(contactID: Uuid, granted: Boolean) =
    contactPreferences.setTelemetryPermissions(contactID, granted)
