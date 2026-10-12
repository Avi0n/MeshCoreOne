// PortedFrom: MC1Services/Tests/MC1ServicesTests/DeduplicationKeyTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.model.DeduplicationKey
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageKeyAcceptanceTest {
    @OriginalCase("DeduplicationKeyTests::Content-based key uses the expected prefix for DM and channel scopes(contactID : UUID ? , channelIndex : UInt8 ? , senderNodeName : String ? , timestamp : UInt32 , content : String , expectedPrefix : String)")
    @Test fun originalPrefixParameterFamilyRetainsAllFourRows() {
        assertTrue(DeduplicationKey.contentBased(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"),
            null, null, 1000u, "hello").startsWith("dm-AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE-1000-"))
        assertTrue(DeduplicationKey.contentBased(null, 3u, "Alice", 2000u, "test").startsWith("ch-3-2000-Alice-"))
        assertTrue(DeduplicationKey.contentBased(null, 0u, null, 500u, "msg").startsWith("ch-0-500--"))
        assertTrue(DeduplicationKey.contentBased(null, null, null, 100u, "x").startsWith("dm-unknown-100-"))
    }
}
