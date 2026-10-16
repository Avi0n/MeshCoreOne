package one.meshcore.shared.persistence

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import one.meshcore.shared.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal class RetryProcessDriver(private val onPartialState: () -> Unit) : SQLiteDriver {
    private val backing = BundledSQLiteDriver()
    private var armed = false
    private var beforeMessage: List<Map<String, Any?>>? = null
    private var beforePending: List<Map<String, Any?>>? = null
    var deleteHits = 0
        private set
    var insertHits = 0
        private set

    fun arm() {
        armed = true
    }

    override val hasConnectionPool: Boolean
        get() = backing.hasConnectionPool

    override fun open(fileName: String): SQLiteConnection {
        val connection = backing.open(fileName)
        return object : SQLiteConnection by connection {
            override fun inTransaction(): Boolean = connection.inTransaction()

            override fun prepare(sql: String): SQLiteStatement = TrackedStatement(connection.prepare(sql)) { bindings ->
                if (armed && isTargetDelete(sql, bindings)) {
                    assertTrue(connection.inTransaction())
                    assertEquals(1, ++deleteHits)
                    beforeMessage = query(connection, MESSAGE_SQL)
                    beforePending = query(connection, PENDING_SQL)
                    assertEquals(1, requireNotNull(beforeMessage).size)
                    assertEquals(MessageStatus.FAILED.raw.toLong(), requireNotNull(beforeMessage).single()[STATUS])
                    assertEquals(
                        RetryProcessRows.oldRows.map {
                            it.id.value
                        }.sorted(),
                        requireNotNull(beforePending).map { it["id"] },
                    )
                }
                if (armed && isTargetInsert(sql, bindings)) {
                    assertTrue(connection.inTransaction())
                    assertEquals(1, deleteHits)
                    assertEquals(1, ++insertHits)
                    val message = requireNotNull(beforeMessage).single().toMutableMap()
                    message[STATUS] = MessageStatus.PENDING.raw.toLong()
                    assertEquals(listOf(message), query(connection, MESSAGE_SQL))
                    val controls = requireNotNull(beforePending).filter {
                        it["messageID"] !=
                            RetryProcessRows.messageID.value
                    }
                    assertEquals(controls, query(connection, PENDING_SQL))
                    onPartialState()
                }
            }
        }
    }

    private fun isTargetDelete(sql: String, bindings: Map<Int, String?>): Boolean =
        sql == DELETE_SQL && bindings[ID_BIND] == RetryProcessRows.messageID.value

    private fun isTargetInsert(sql: String, bindings: Map<Int, String?>): Boolean = sql == INSERT_SQL &&
        bindings[ID_BIND] == RetryProcessRows.replacementID.value &&
        bindings[RADIO_BIND] == RetryProcessRows.radioA.value &&
        bindings[MESSAGE_BIND] == RetryProcessRows.messageID.value

    private fun query(connection: SQLiteConnection, sql: String): List<Map<String, Any?>> =
        connection.prepare(sql).use { statement ->
            buildList {
                while (statement.step()) {
                    add(
                        buildMap {
                            for (index in 0 until statement.getColumnCount()) {
                                val value: Any? = when (statement.getColumnType(index)) {
                                    INTEGER -> statement.getLong(index)
                                    FLOAT -> statement.getDouble(index)
                                    TEXT -> statement.getText(index)
                                    BLOB -> statement.getBlob(index).toList()
                                    NULL -> null
                                    else -> error("Unknown SQLite column type")
                                }
                                put(statement.getColumnName(index), value)
                            }
                        },
                    )
                }
            }
        }

    private class TrackedStatement(
        private val backing: SQLiteStatement,
        private val beforeStep: (Map<Int, String?>) -> Unit,
    ) : SQLiteStatement by backing {
        private val text = mutableMapOf<Int, String?>()
        override fun bindText(index: Int, value: String) {
            text[index] = value
            backing.bindText(index, value)
        }
        override fun bindNull(index: Int) {
            text[index] = null
            backing.bindNull(index)
        }
        override fun bindLong(index: Int, value: Long) {
            text[index] = null
            backing.bindLong(index, value)
        }
        override fun bindDouble(index: Int, value: Double) {
            text[index] = null
            backing.bindDouble(index, value)
        }
        override fun bindBlob(index: Int, value: ByteArray) {
            text[index] = null
            backing.bindBlob(index, value)
        }
        override fun reset() {
            text.clear()
            backing.reset()
        }
        override fun clearBindings() {
            text.clear()
            backing.clearBindings()
        }
        override fun step(): Boolean {
            beforeStep(text)
            return backing.step()
        }
    }

    private companion object {
        const val ID_BIND = 1
        const val RADIO_BIND = 2
        const val MESSAGE_BIND = 3
        const val INTEGER = 1
        const val FLOAT = 2
        const val TEXT = 3
        const val BLOB = 4
        const val NULL = 5
        const val STATUS = "statusRawValue"
        const val DELETE_SQL = "DELETE FROM pending_send WHERE messageID = ?"
        const val INSERT_SQL = "INSERT OR ABORT INTO `pending_send` " +
            "(`id`,`radioID`,`messageID`,`kindRawValue`,`isResend`,`messageText`,`messageTimestamp`," +
            "`sequence`,`enqueuedAt`,`contactID`,`channelIndex`,`localNodeName`,`attemptCount`) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
        const val PENDING_SQL = "SELECT * FROM pending_send ORDER BY id"
        val MESSAGE_SQL = "SELECT * FROM message WHERE id = '" + RetryProcessRows.messageID.value + "'"
    }
}
