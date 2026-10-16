package one.meshcore.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalNodeNameResolutionTest {
    @Test
    fun disconnectedFixturesKeepTheirLocalIdentity() {
        assertEquals(
            "Demo Node",
            resolveLocalNodeName(
                connected = false,
                connectedName = null,
                fixturesActive = true,
                fixtureName = "Demo Node",
            ),
        )
    }

    @Test
    fun liveConnectionWinsOverFixtureIdentity() {
        assertEquals(
            "Field Radio",
            resolveLocalNodeName(
                connected = true,
                connectedName = "Field Radio",
                fixturesActive = true,
                fixtureName = "Demo Node",
            ),
        )
    }

    @Test
    fun disconnectedAppWithoutFixturesHasNoLocalIdentity() {
        assertNull(
            resolveLocalNodeName(
                connected = false,
                connectedName = "Old Radio",
                fixturesActive = false,
                fixtureName = "Demo Node",
            ),
        )
    }
}
