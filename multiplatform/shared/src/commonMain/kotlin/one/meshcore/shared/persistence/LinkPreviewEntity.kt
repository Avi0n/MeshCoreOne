package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row backing the link-preview cache, mirroring the Swift `LinkPreviewDataDTO` (§6.7). Keyed by the
 * full [url]. The hero/icon bytes persist as nullable BLOBs; [fetchedAtEpochSeconds] is stored but never
 * used to invalidate (no TTL). Previews are regenerable, so this table is NOT part of the backup envelope
 * and rides the database's destructive-fallback policy.
 */
@Entity(tableName = "link_preview")
data class LinkPreviewEntity(
    @PrimaryKey val url: String,
    val title: String? = null,
    val imageBytes: ByteArray? = null,
    val iconBytes: ByteArray? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val fetchedAtEpochSeconds: Double = 0.0,
)
