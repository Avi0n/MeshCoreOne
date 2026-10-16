package one.meshcore.shared.persistence

import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.setContactFavorite
import one.meshcore.shared.contact.setTelemetryPermissions
import one.meshcore.shared.contact.updateContactAvatar
import one.meshcore.shared.contact.updateContactOCVSettings
import one.meshcore.shared.contact.updateContactPreferences
import one.meshcore.shared.model.Uuid

internal enum class RoomContactPreferencePeerCase(val notifications: Int, val isCommand: Boolean) {
    NICKNAME(1, false),
    BLOCK(1, false),
    LOCAL_FAVORITE(1, false),
    AVATAR(1, false),
    OCV(0, false),
    FAVORITE(0, true),
    TELEMETRY(0, true),
    ;

    suspend fun invoke(service: ContactService, id: Uuid) {
        when (this) {
            NICKNAME -> service.updateContactPreferences(id, nickname = "  Selected nickname  ")
            BLOCK -> service.updateContactPreferences(id, isBlocked = true)
            LOCAL_FAVORITE -> service.updateContactPreferences(id, isFavorite = true)
            AVATAR -> service.updateContactAvatar(id, SELECTED_AVATAR.copyOf())
            OCV -> service.updateContactOCVSettings(id, SELECTED_PRESET, SELECTED_CURVE, isCurrent = { true })
            FAVORITE -> service.setContactFavorite(id, true)
            TELEMETRY -> service.setTelemetryPermissions(id, true)
        }
    }

    fun expected(current: ContactEntity): ContactEntity = when (this) {
        NICKNAME -> current.copy(nickname = "Selected nickname")
        BLOCK -> current.copy(isBlocked = true, unreadCount = if (current.isBlocked) current.unreadCount else 0)
        LOCAL_FAVORITE -> current.copy(isFavorite = true)
        AVATAR -> current.copy(avatarImageData = SELECTED_AVATAR.copyOf())
        OCV -> current.copy(ocvPreset = SELECTED_PRESET, customOCVArrayString = SELECTED_CURVE)
        FAVORITE -> current.copy(flags = commandFlags, isFavorite = true)
        TELEMETRY -> current.copy(flags = commandFlags)
    }

    val commandFlags: Int get() = when (this) {
        FAVORITE -> BASE_FLAGS or FAVORITE_BIT
        TELEMETRY -> BASE_FLAGS or TELEMETRY_MASK
        else -> error("This local edit has no command flags")
    }

    companion object {
        const val BASE_FLAGS = 0x10
        private const val FAVORITE_BIT = 0x01
        private const val TELEMETRY_MASK = 0x0E
        private const val SELECTED_PRESET = "selected curve"
        private const val SELECTED_CURVE = "[3.2,3.5,3.9]"
        private val SELECTED_AVATAR = byteArrayOf(41, 42)
    }
}
