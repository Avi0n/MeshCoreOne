// PortedFrom: MC1Tests/Utilities/MeshCoreURLParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.deeplinks.ChannelJoinFloodScopeApplier
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelJoinOriginalPredicateTest {
    @Test
    fun originalUnpaddedRegionNameSelectsItsExactScope() {
        assertEquals(
            ChannelFloodScope.Region("testregion"),
            ChannelJoinFloodScopeApplier.preferredFloodScope("testregion"),
        )
    }

    @Test
    fun originalChannelNotFoundWriteKeepsInheritAndChannelIdentity() = runTest {
        val channel = ChannelDTO(
            radioId = RadioId(UUID.randomUUID()),
            index = 1u,
            name = "Ops",
            secret = Bytes(ByteArray(ProtocolLimits.CHANNEL_SECRET_SIZE) { 0xAB.toByte() }),
        )
        var attempts = 0
        val updated = ChannelJoinFloodScopeApplier.applyIfNeeded(channel, "testregion") { id, scope ->
            attempts++
            assertEquals(channel.id, id)
            assertEquals(ChannelFloodScope.Region("testregion"), scope)
            throw PersistenceStoreException(PersistenceStoreError.ChannelNotFound)
        }
        assertEquals(1, attempts)
        assertEquals(ChannelFloodScope.Inherit, updated.floodScope)
        assertEquals(channel.id, updated.id)
    }
}
