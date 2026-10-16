package one.meshcore.android

import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.MessageEntity
import one.meshcore.shared.persistence.PendingSendEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal class AppPendingColdRows(private val database: MeshDatabase) {
    suspend fun seedPending() {
        database.messageDao().upsert(expectedMessage())
        database.pendingSendDao().insert(expectedPending())
        assertPending()
    }

    suspend fun assertPending() {
        val messages = database.messageDao().listAll()
        val row = messages.single { it.id == MESSAGE_ID }
        assertEquals(expectedMessage(), row)
        assertEquals(listOf(expectedPending()), database.pendingSendDao().listByRadio(RADIO))
        assertEquals(1, messages.count { it.directionRawValue == MessageDirection.OUTGOING.raw })
        database.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM pending_send") { statement ->
                assertTrue(statement.step())
                assertEquals(1L, statement.getLong(0))
            }
        }
        val channel = database.channelDao().listByRadio(RADIO).single { it.index == SLOT }
        assertEquals(CHANNEL, channel.name)
        assertTrue(channel.secret.contentEquals(SECRET))
    }

    suspend fun snapshot(): String {
        val result = JSONObject()
        for ((table, rows) in BackupPostcommitRows(database).snapshot()) {
            val values = JSONArray()
            for (row in rows) {
                val columns = JSONObject()
                for ((column, raw) in row) {
                    val pair = raw as Pair<*, *>
                    val value = when (val stored = pair.second) {
                        null -> JSONObject.NULL
                        is List<*> -> JSONArray(stored)
                        else -> stored
                    }
                    columns.put(column, JSONArray().put(pair.first).put(value))
                }
                values.put(columns)
            }
            result.put(table, values)
        }
        assertEquals(TABLE_COUNT, result.length())
        return result.toString()
    }

    suspend fun assertSchema() = database.useConnection(isReadOnly = true) { connection ->
        connection.usePrepared("PRAGMA user_version") { statement ->
            assertTrue(statement.step())
            assertEquals(SCHEMA, statement.getLong(0))
            assertTrue(!statement.step())
        }
    }

    private fun expectedMessage() = MessageEntity(
        id = MESSAGE_ID, radioID = RADIO, text = TEXT, timestamp = TIMESTAMP,
        createdAt = TIMESTAMP.toDouble(), sortDate = TIMESTAMP.toDouble(),
        directionRawValue = MessageDirection.OUTGOING.raw, statusRawValue = MessageStatus.PENDING.raw,
        textTypeRawValue = 0, pathLength = 0, isRead = true, heardRepeats = 0, sendCount = 0,
        retryAttempt = 0, maxRetryAttempts = 0, linkPreviewFetched = false, containsSelfMention = false,
        mentionSeen = false, failureSeen = false, timestampCorrected = false, routeTypeRawValue = -1,
        contactID = null, channelIndex = SLOT, ackCode = null, snr = null, pathNodes = null,
        senderKeyPrefix = null, senderNodeName = null, replyToID = null, roundTripTime = null,
        deduplicationKey = null, linkPreviewURL = null, linkPreviewTitle = null, linkPreviewImageData = null,
        linkPreviewIconData = null, senderTimestamp = null, reactionSummary = null, regionScope = null,
        regionScopeMatches = emptyList(),
    )

    private fun expectedPending() = PendingSendEntity(
        id = PENDING_ID, radioID = RADIO, messageID = MESSAGE_ID, kindRawValue = 1,
        isResend = false, messageText = TEXT, messageTimestamp = TIMESTAMP, sequence = 1,
        enqueuedAt = TIMESTAMP.toDouble(), channelIndex = SLOT, localNodeName = "Synthetic offline node",
        attemptCount = 0,
    )

    companion object {
        val RADIO = Uuid("A0000000-0000-0000-0000-000000000001")
        val MESSAGE_ID = Uuid("30000000-0000-0000-0000-000000000099")
        val PENDING_ID = Uuid("90000000-0000-0000-0000-000000000099")
        const val CHANNEL = "MC1 offline pending fixture"
        const val TEXT = "MC1 pending survives cold default app"
        const val DRAFT = "Nonempty offline composer draft"
        const val SLOT = 1
        const val TIMESTAMP = 1_700_000_300L
        const val SCHEMA = 11L
        const val TABLE_COUNT = 14
        val SECRET = ByteArray(16) { (it + 33).toByte() }
    }
}
