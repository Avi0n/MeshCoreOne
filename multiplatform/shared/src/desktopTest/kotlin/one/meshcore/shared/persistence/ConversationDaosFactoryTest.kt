package one.meshcore.shared.persistence

import java.io.File
import kotlin.test.Test
import kotlin.test.assertSame

class ConversationDaosFactoryTest {
    @Test
    fun productionBundleUsesEachDatabaseDao() {
        val dbFile = File.createTempFile("conversation-daos", ".db").also { it.delete() }
        val db = meshDatabase(dbFile.absolutePath)
        try {
            val daos = db.conversationDaos()
            assertSame(db.contactDao(), daos.contact)
            assertSame(db.channelDao(), daos.channel)
            assertSame(db.messageDao(), daos.message)
            assertSame(db.remoteNodeSessionDao(), daos.remoteNodeSession)
            assertSame(db.roomMessageDao(), daos.roomMessage)
            assertSame(db.pendingSendDao(), daos.pendingSend)
            assertSame(db.reactionDao(), daos.reaction)
            assertSame(db.messageRepeatDao(), daos.messageRepeat)
        } finally {
            db.close()
            dbFile.delete()
        }
    }
}
