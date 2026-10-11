// AndroidOnly: WP-407 Actual link type is session-token bound, never inferred from saved connection methods.
package com.meshcoreone.android.app.platform

import com.meshcoreone.android.app.container.AndroidRuntimeLinkFactory
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TransportType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TransportRegistrationTest {
    @Test fun aStaleLinkCannotClearTheCurrentGenerationTransport() = runBlocking {
        val factory = AndroidRuntimeLinkFactory({ error("This test must not create a Bluetooth link") })
        val first = factory.create(ConnectionTarget.WiFi("radio.test", 5000u))
        val second = factory.create(ConnectionTarget.WiFi("other.test", 5001u))
        val epoch = ProcessEpoch(UUID.randomUUID())
        val radio = RadioId(UUID.randomUUID())
        val old = SessionToken(epoch, Generation(1), radio)
        val current = SessionToken(epoch, Generation(2), radio)
        assertNull(factory.transportFor(old))
        first.setSessionLive(old)
        assertEquals(TransportType.WIFI, factory.transportFor(old))
        second.setSessionLive(current)
        assertNull(factory.transportFor(old))
        first.setSessionLive(null)
        assertEquals(TransportType.WIFI, factory.transportFor(current))
        second.setSessionLive(null)
        assertNull(factory.transportFor(current))
    }
}
