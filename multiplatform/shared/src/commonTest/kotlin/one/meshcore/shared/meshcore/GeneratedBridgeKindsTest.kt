package one.meshcore.shared.meshcore

import kotlin.test.Test
import kotlin.test.assertTrue

class GeneratedBridgeKindsTest {
    @Test
    fun generatedSetsMatchTheFrozenEnvelope() {
        assertTrue("rxLogData" in GeneratedBridgeKinds.events)
        assertTrue("connectionStateChanged" in GeneratedBridgeKinds.events)
        assertTrue("ok" in GeneratedBridgeKinds.results)
        assertTrue("deviceCapabilities" in GeneratedBridgeKinds.results)
    }
}
