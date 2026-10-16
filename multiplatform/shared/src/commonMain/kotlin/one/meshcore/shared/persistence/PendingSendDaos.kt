package one.meshcore.shared.persistence

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import one.meshcore.shared.model.Uuid

/** Ordered fetches and sequence lookups for the durable send queue. */
@Dao
interface PendingSendQueryDao {
    @Query("SELECT * FROM pending_send WHERE radioID = :radioID ORDER BY sequence")
    suspend fun listByRadio(radioID: Uuid): List<PendingSendEntity>

    @Query("SELECT * FROM pending_send WHERE messageID = :messageID ORDER BY sequence")
    suspend fun listByMessage(messageID: Uuid): List<PendingSendEntity>

    @Query("SELECT * FROM pending_send WHERE messageID = :messageID LIMIT 1")
    suspend fun firstByMessage(messageID: Uuid): PendingSendEntity?

    @Query("SELECT MAX(sequence) FROM pending_send WHERE radioID = :radioID")
    suspend fun maxSequence(radioID: Uuid): Int?

    @Query("SELECT EXISTS(SELECT 1 FROM pending_send WHERE messageID = :messageID)")
    suspend fun exists(messageID: Uuid): Boolean

    @Query("SELECT * FROM pending_send WHERE attemptCount IS NULL")
    suspend fun listLegacyAttemptCount(): List<PendingSendEntity>

    @Query("SELECT * FROM pending_send WHERE radioID NOT IN (SELECT radioID FROM device)")
    suspend fun listOrphans(): List<PendingSendEntity>
}

/** Inserts, attempt bumps, and the deletes that drop a parked send. */
@Dao
interface PendingSendWriteDao {
    @Query("UPDATE pending_send SET attemptCount = :count WHERE id = :id")
    suspend fun setAttemptCount(id: Uuid, count: Int)

    @Insert
    suspend fun insert(entity: PendingSendEntity)

    @Upsert
    suspend fun upsert(entity: PendingSendEntity)

    @Delete
    suspend fun delete(entity: PendingSendEntity)

    @Query("DELETE FROM pending_send WHERE id = :id")
    suspend fun deleteById(id: Uuid)

    @Query("DELETE FROM pending_send WHERE messageID = :messageID")
    suspend fun deleteByMessage(messageID: Uuid)

    @Query("DELETE FROM pending_send WHERE messageID IN (:ids)")
    suspend fun deleteByMessageIds(ids: List<Uuid>)

    @Query("DELETE FROM pending_send WHERE contactID = :contactID")
    suspend fun deleteByContact(contactID: Uuid)

    @Query("DELETE FROM pending_send WHERE radioID = :radioID")
    suspend fun deleteByRadio(radioID: Uuid)
}

@Dao
interface PendingSendDao :
    PendingSendQueryDao,
    PendingSendWriteDao
