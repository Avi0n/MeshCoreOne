package one.meshcore.shared.backup

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import one.meshcore.shared.persistence.MeshDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.Base64

internal class BackupImportNativeRows(val database: MeshDatabase) {
    val incoming = envelope(foreign = false)
    private val local = envelope(foreign = true)

    suspend fun seed(): Map<String, List<Map<String, Any?>>> {
        assertEquals(INSERTED, RoomBackupImporter.importEnvelope(database, local).totalInserted)
        return snapshot().also { assertCounts(it, copies = 1) }
    }

    suspend fun snapshot(): Map<String, List<Map<String, Any?>>> =
        database.useConnection(isReadOnly = false) { connection ->
            TABLES.associateWith { table ->
                connection.usePrepared("SELECT * FROM $table ORDER BY id") { statement ->
                    buildList {
                        while (statement.step()) {
                            add(
                                (0 until statement.getColumnCount()).associate { index ->
                                    val type = statement.getColumnType(index)
                                    val value: Any? = when (type) {
                                        SQLITE_DATA_INTEGER -> statement.getLong(index)
                                        SQLITE_DATA_FLOAT -> statement.getDouble(index)
                                        SQLITE_DATA_TEXT -> statement.getText(index)
                                        SQLITE_DATA_BLOB -> statement.getBlob(index).toList()
                                        SQLITE_DATA_NULL -> null
                                        else -> error("Unexpected SQLite type $type")
                                    }
                                    statement.getColumnName(index) to (type to value)
                                },
                            )
                        }
                    }
                }
            }
        }

    fun assertCounts(snapshot: Map<String, List<Map<String, Any?>>>, copies: Int) {
        for (table in TABLES) {
            val expected = when (table) {
                "pending_send" -> 0
                "message" -> copies * MESSAGES
                else -> copies
            }
            assertEquals(table, expected, snapshot.getValue(table).size)
        }
    }

    suspend fun assertLive(before: Map<String, List<Map<String, Any?>>>) {
        val after = snapshot()
        assertCounts(after, copies = 2)
        for (table in TABLES) assertTrue(table, after.getValue(table).containsAll(before.getValue(table)))
        val radio = incoming.devices.single().radioID
        val device = database.deviceDao().listAll().single { it.radioID == radio }
        assertFalse(device.isActive)
        assertTrue(device.connectionMethods.isEmpty())
        assertTrue(device.publicKey.contentEquals(incoming.devices.single().publicKey))
        val contact = database.contactDao().listAll().single { it.radioID == radio }
        assertEquals(incoming.contacts.single().nickname, contact.nickname)
        assertTrue(contact.isPinned && contact.isFavorite)
        val channel = database.channelDao().listAll().single { it.radioID == radio }
        assertTrue(channel.secret.contentEquals(incoming.channels.single().secret))
        assertEquals(incoming.channels.single().regionScope, channel.regionScope)
        val messages = database.messageDao().listAll().filter { it.radioID == radio }
        assertEquals(MESSAGES, messages.size)
        assertTrue(messages.single { it.channelIndex == null }.contactID == contact.id)
        val channelMessage = messages.single { it.channelIndex != null }
        assertEquals(channel.index, channelMessage.channelIndex)
        val reaction = database.reactionDao().listAll().single { it.radioID == radio }
        assertEquals(channelMessage.id, reaction.messageID)
        assertEquals(1, database.messageRepeatDao().listAll().count { it.messageID == channelMessage.id })
        val session = database.remoteNodeSessionDao().getAll().single { it.radioID == radio }
        assertFalse(session.isConnected)
        assertEquals(1, database.roomMessageDao().listAll().count { it.sessionID == session.id })
        val path = database.savedTracePathDao().listAll().single { it.radioID == radio }
        assertEquals(1, database.tracePathRunDao().listByPath(path.id).size)
        assertEquals(incoming.messages.maxOf { it.createdAt }, channel.lastMessageDate)
    }

    private fun envelope(foreign: Boolean): AppBackupEnvelope {
        val input = Json.parseToJsonElement(
            InstrumentationRegistry.getInstrumentation().context.assets.open(
                "backup-cancellation.json",
            ).bufferedReader().use {
                it.readText()
            },
        )
        val transformed = rewrite(input, foreign = foreign)
        val decoded = decodeBackupEnvelope(transformed.toString())
        return decoded.copy(
            devices = decoded.devices.map {
                it.copy(connectionMethods = emptyList(), isActive = false)
            },
        )
    }

    private fun rewrite(element: JsonElement, key: String = "", foreign: Boolean): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (name, value) -> rewrite(value, name, foreign) })
        is JsonArray -> JsonArray(element.map { rewrite(it, key, foreign) })
        is JsonPrimitive -> when {
            !foreign || !element.isString -> element
            UUID.matches(element.content) -> JsonPrimitive(
                (element.content.first().digitToInt(HEX_BASE) xor UUID_MASK).toString(HEX_BASE).uppercase() +
                    element.content.drop(1),
            )
            key in setOf("publicKey", "nodePublicKey") -> JsonPrimitive(
                Base64.getEncoder().encodeToString(
                    Base64.getDecoder().decode(element.content).map {
                        (it.toInt() xor KEY_MASK).toByte()
                    }.toByteArray(),
                ),
            )
            else -> element
        }
    }

    companion object {
        const val INSERTED = 13
        private const val MESSAGES = 2
        private const val HEX_BASE = 16
        private const val UUID_MASK = 8
        private const val KEY_MASK = 0x40
        private val UUID = Regex("[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}")
        private val TABLES = listOf(
            "device", "contact", "channel", "message", "reaction", "message_repeat", "remote_node_session",
            "room_message", "saved_trace_path", "blocked_channel_sender", "node_status_snapshot", "discovered_node",
            "trace_path_run", "pending_send",
        )
    }
}
