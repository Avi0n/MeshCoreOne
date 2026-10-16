package one.meshcore.shared.persistence

import java.io.File
import kotlin.test.Test
import kotlin.test.assertSame

class ChatDaosFactoryTest {
    @Test
    fun productionBundleUsesEachDatabaseDaoIncludingDevice() {
        val dbFile = File.createTempFile("chat-daos", ".db").also { it.delete() }
        val db = meshDatabase(dbFile.absolutePath)
        try {
            val daos = db.chatDaos()
            assertSame(db.messageDao(), daos.message)
            assertSame(db.contactDao(), daos.contact)
            assertSame(db.channelDao(), daos.channel)
            assertSame(db.messageRepeatDao(), daos.messageRepeat)
            assertSame(db.pendingSendDao(), daos.pendingSend)
            assertSame(db.reactionDao(), daos.reaction)
            assertSame(db.deviceDao(), daos.device)
        } finally {
            db.close()
            dbFile.delete()
        }
    }
}
