package one.meshcore.shared.meshcore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The Kotlin side of the JNI boundary freeze: every golden vector the Swift producer emits under
 * `fixtures/bridge/` must decode here. The count assertions (53 events, 29 results) fail the instant
 * a new fixture is added on the Swift side without a matching Kotlin decode path, so the two sides
 * cannot silently drift. Field-level checks on a representative subset guard the scalar conventions
 * (Base64 data, integer epoch dates, doubles, nullable-as-null).
 */
class BridgeGoldenDecodeTest {

    @Test
    fun everyEventFixtureDecodes() {
        val fixtures = readBridgeFixtures("events")
        assertEquals(53, fixtures.size, "expected 53 event fixtures; found ${fixtures.keys.sorted()}")
        // decodeMeshEvent throws on an unhandled kind, so decoding every fixture is the assertion.
        val decoded = fixtures.mapValues { (_, json) -> decodeMeshEvent(json) }
        assertEquals(53, decoded.size)
    }

    @Test
    fun everyResultFixtureDecodes() {
        val fixtures = readBridgeFixtures("results")
        assertEquals(29, fixtures.size, "expected 29 result fixtures; found ${fixtures.keys.sorted()}")
        // decodeCommandResult throws on an unhandled kind, so decoding every fixture is the assertion.
        val decoded = fixtures.mapValues { (_, json) -> decodeCommandResult(json) }
        assertEquals(29, decoded.size)
    }

    @Test
    fun selfInfoFieldsDecode() {
        val event = decodeMeshEvent(readBridgeFixtures("events").getValue("selfInfo"))
        val info = (event as MeshEvent.SelfInfoReceived).info
        assertEquals("Base Camp", info.name)
        assertEquals(915.0, info.radioFrequency)
        assertEquals(125.0, info.radioBandwidth)
        assertEquals(1, info.advertisementType)
        assertEquals(32, info.publicKey.size)
        assertEquals(true, info.manualAddContacts)
    }

    @Test
    fun contactFieldsDecode() {
        val event = decodeMeshEvent(readBridgeFixtures("events").getValue("contact"))
        val contact = (event as MeshEvent.ContactReceived).contact
        assertEquals("Ridgeline", contact.advertisedName)
        assertEquals(2, contact.typeRawValue)
        assertEquals(3, contact.flags)
        assertEquals(2, contact.outPath.size)
        assertEquals(1_700_000_500L, contact.lastModified)
        assertEquals(32, contact.publicKey.size)
    }

    @Test
    fun contactMessageFieldsDecode() {
        val event = decodeMeshEvent(readBridgeFixtures("events").getValue("contactMessageReceived"))
        val message = (event as MeshEvent.ContactMessageReceived).message
        assertEquals("Alice: Hello mesh!", message.text)
        assertEquals(7.25, message.snr)
        assertEquals(3, message.pathLength)
        assertEquals(64, message.signature?.size)
        assertEquals(1_700_000_000L, message.senderTimestamp)
    }

    @Test
    fun loginSuccessFieldsDecode() {
        val event = decodeMeshEvent(readBridgeFixtures("events").getValue("loginSuccess"))
        val info = (event as MeshEvent.LoginSuccess).login
        assertEquals(3, info.permissions)
        assertEquals(true, info.isAdmin)
        assertEquals(6, info.publicKeyPrefix.size)
        assertEquals(1_700_000_000L, info.serverTime)
    }

    @Test
    fun batteryFieldsDecode() {
        val event = decodeMeshEvent(readBridgeFixtures("events").getValue("battery"))
        val battery = (event as MeshEvent.BatteryReceived).battery
        assertEquals(3700, battery.level)
        assertEquals(128L, battery.usedStorageKB)
        assertEquals(4096L, battery.totalStorageKB)
    }

    @Test
    fun statsCoreFieldsDecode() {
        val event = decodeMeshEvent(readBridgeFixtures("events").getValue("statsCore"))
        val stats = (event as MeshEvent.StatsCore).stats
        assertEquals(86_400L, stats.uptimeSeconds)
        assertEquals(3, stats.errors)
        assertEquals(5, stats.queueLength)
        assertEquals(3700, stats.batteryMV)
    }

    @Test
    fun errorEventDecodes() {
        val event = decodeMeshEvent(readBridgeFixtures("events").getValue("error"))
        assertEquals(6, (event as MeshEvent.ErrorReported).code)
    }

    @Test
    fun traceDataNullHashBytesDecode() {
        val event = decodeMeshEvent(readBridgeFixtures("events").getValue("traceData"))
        val trace = (event as MeshEvent.TraceData).trace
        assertEquals(2, trace.path.size)
        assertEquals(1, trace.path[0].hashBytes?.size)
        assertNull(trace.path[1].hashBytes)
        assertEquals(287_454_020L, trace.tag)
    }

    @Test
    fun logDataNullScalarsDecode() {
        val event = decodeMeshEvent(readBridgeFixtures("events").getValue("logData"))
        val data = (event as MeshEvent.LogData).data
        assertNull(data.snr)
        assertNull(data.rssi)
        assertEquals(3, data.payload.size)
    }

    @Test
    fun messageResultUnionDecodes() {
        val result = decodeCommandResult(readBridgeFixtures("results").getValue("messageResult"))
        val message = (result as CommandResult.MessageResult).message
        val contact = (message as GetMessageResult.ContactMessageReceived).message
        assertEquals("Alice: Hello mesh!", contact.text)
    }

    @Test
    fun okResultDecodes() {
        val result = decodeCommandResult(readBridgeFixtures("results").getValue("ok"))
        assertEquals(CommandResult.Ok, result)
    }

    @Test
    fun channelsResultDecodes() {
        val result = decodeCommandResult(readBridgeFixtures("results").getValue("channels"))
        val channels = result as CommandResult.ChannelsResult
        assertEquals(1, channels.received.size)
        assertEquals("Public", channels.received.first().name)
        assertEquals(listOf(3, 5), channels.missing)
    }

    @Test
    fun defaultFloodScopeDecodes() {
        val event = decodeMeshEvent(readBridgeFixtures("events").getValue("defaultFloodScope"))
        val scope = (event as MeshEvent.DefaultFloodScopeReceived).scope
        assertEquals("Europe", scope?.name)
        assertEquals(16, scope?.scopeKey?.size)
    }

    @Test
    fun rejectsWrongVersion() {
        val bad = """{"v":2,"kind":"ok","payload":{}}"""
        assertFailsWith<BridgeDecodingException> { decodeCommandResult(bad) }
    }
}
