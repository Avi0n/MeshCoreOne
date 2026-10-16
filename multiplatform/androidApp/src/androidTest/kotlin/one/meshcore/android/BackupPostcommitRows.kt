package one.meshcore.android

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import one.meshcore.shared.persistence.MeshDatabase
import org.junit.Assert.assertEquals

internal class BackupPostcommitRows(val database: MeshDatabase) {
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

    companion object {
        const val INSERTED = 13
        private const val MESSAGES = 2
        private val TABLES = listOf(
            "device", "contact", "channel", "message", "reaction", "message_repeat", "remote_node_session",
            "room_message", "saved_trace_path", "blocked_channel_sender", "node_status_snapshot", "discovered_node",
            "trace_path_run", "pending_send",
        )
    }
}
