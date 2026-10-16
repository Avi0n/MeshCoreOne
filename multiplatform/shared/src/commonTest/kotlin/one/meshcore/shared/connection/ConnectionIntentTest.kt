package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionIntentTest {
    @Test
    fun onlyUserDisconnectedPersistsTheDisconnectBit() {
        assertTrue(ConnectionIntent.UserDisconnected.persistedDisconnectFlag)
        assertFalse(ConnectionIntent.None.persistedDisconnectFlag)
        assertFalse(ConnectionIntent.WantsConnection().persistedDisconnectFlag)
        assertFalse(ConnectionIntent.WantsConnection(forceFullSync = true).persistedDisconnectFlag)
    }

    @Test
    fun restoreReadsUserDisconnectedFromTheBitElseNone() {
        assertEquals(ConnectionIntent.UserDisconnected, ConnectionIntent.restored(userExplicitlyDisconnected = true))
        assertEquals(ConnectionIntent.None, ConnectionIntent.restored(userExplicitlyDisconnected = false))
    }

    @Test
    fun conveniencesReflectTheCase() {
        assertTrue(ConnectionIntent.UserDisconnected.isUserDisconnected)
        assertFalse(ConnectionIntent.UserDisconnected.wantsConnection)
        assertTrue(ConnectionIntent.WantsConnection().wantsConnection)
        assertFalse(ConnectionIntent.WantsConnection().isUserDisconnected)
        assertFalse(ConnectionIntent.None.wantsConnection)
        assertFalse(ConnectionIntent.None.isUserDisconnected)
    }
}
