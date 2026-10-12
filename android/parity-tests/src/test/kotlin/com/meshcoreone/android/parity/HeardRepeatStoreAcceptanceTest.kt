// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/HeardRepeatsServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HeardRepeatStoreAcceptanceTest : RoomProcessTest() {
    @OriginalCase("HeardRepeatsServiceTests::exact text disambiguates messages sharing channel and timestamp()")
    @Test fun exactTextDisambiguatesStoredOutgoingRowsWithTheSameChannelAndTimestamp() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val channel: UByte = 3u
        val timestamp = Instant.now().epochSecond.toUInt()
        val aID = UUID.randomUUID()
        val bID = UUID.randomUUID()
        store.saveMessage(MessageDTO(
            id = aID, radioId = radio, channelIndex = channel, text = "message A",
            timestamp = timestamp, status = MessageStatus.SENT,
        ))
        store.saveMessage(MessageDTO(
            id = bID, radioId = radio, channelIndex = channel, text = "message B",
            timestamp = timestamp, status = MessageStatus.SENT,
        ))

        assertEquals(bID, store.findSentChannelMessage(radio, channel, timestamp, "message B")?.id)
        assertEquals(aID, store.findSentChannelMessage(radio, channel, timestamp, "message A")?.id)
        assertEquals("message A", store.fetchMessage(EntityKey(radio, aID))?.text)
        assertEquals("message B", store.fetchMessage(EntityKey(radio, bID))?.text)
        assertEquals(setOf(aID, bID), store.fetchMessages(radio, channel).map { it.id }.toSet())
    }
}
