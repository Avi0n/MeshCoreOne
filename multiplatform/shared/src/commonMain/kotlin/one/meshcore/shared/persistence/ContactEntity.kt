package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

/**
 * Persistence mirror of the SwiftData `Contact` model. The contact type is stored as its raw
 * [typeRawValue] with the typed [ContactEntity.type] accessor, following the `RawValue`-suffix
 * convention.
 */
@Entity(tableName = "contact")
data class ContactEntity(
    @PrimaryKey val id: Uuid,
    val radioID: Uuid,
    val publicKey: ByteArray,
    val name: String,
    val typeRawValue: Int,
    val flags: Int,
    val outPathLength: Int,
    val outPath: ByteArray,
    val lastAdvertTimestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val lastModified: Long,
    /** Phone-clock epoch seconds; null means never heard on air (legacy backups omit the key). */
    val lastHeardTimestamp: Long? = null,
    val isBlocked: Boolean,
    val isMuted: Boolean,
    val isFavorite: Boolean,
    val isPinned: Boolean = false,
    val unreadCount: Int,
    val unreadMentionCount: Int = 0,
    val nickname: String? = null,
    val lastMessageDate: Double? = null,
    val ocvPreset: String? = null,
    val customOCVArrayString: String? = null,
    val avatarImageData: ByteArray? = null,
)

/** Typed accessor over the raw contact-type column; unknown raws fall back to `CHAT`. */
val ContactEntity.type: ContactType
    get() = ContactType.fromRaw(typeRawValue) ?: ContactType.CHAT
