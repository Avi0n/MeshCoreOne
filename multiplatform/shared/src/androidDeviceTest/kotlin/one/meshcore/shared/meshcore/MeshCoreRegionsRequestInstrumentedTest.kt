package one.meshcore.shared.meshcore

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.RegionDiscoveryService
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Suppress("MagicNumber") // Exact request vectors shared with the Swift packet-builder tests.
class MeshCoreRegionsRequestInstrumentedTest {
    @Test
    fun nativeRegionRequestsReverseCompleteHopsWithoutReversingHashBytes() = runBlocking {
        val transport = MeshCoreSessionInstrumentedTest.FakeTransport()
        MeshCoreSession(transport).use { session ->
            MeshCoreSessionInstrumentedTest.connect(session, transport)
            val publicKey = ByteArray(32) { 0xAA.toByte() }
            val cases = listOf(
                ReplyPath(0x02, byteArrayOf(0x11, 0x22), byteArrayOf(0x22, 0x11)),
                ReplyPath(0x41, byteArrayOf(0x11, 0x22), byteArrayOf(0x11, 0x22)),
                ReplyPath(0x42, byteArrayOf(0x11, 0x22, 0x33, 0x44), byteArrayOf(0x33, 0x44, 0x11, 0x22)),
                ReplyPath(0x81, byteArrayOf(0x11, 0x22, 0x33), byteArrayOf(0x11, 0x22, 0x33)),
                ReplyPath(
                    0x82,
                    byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
                    byteArrayOf(0x44, 0x55, 0x66, 0x11, 0x22, 0x33),
                ),
            )
            for (case in cases) {
                val stored = ContactDto(
                    id = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
                    radioID = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB"),
                    publicKey = publicKey,
                    typeRawValue = 2,
                    flags = 0,
                    outPathLength = case.length,
                    outPath = case.outPath,
                    name = "Hilltop",
                    lastAdvertTimestamp = 0,
                    latitude = 0.0,
                    longitude = 0.0,
                    lastModified = 0,
                    isBlocked = false,
                    isMuted = false,
                    isFavorite = false,
                    unreadCount = 0,
                )
                val contact = RegionDiscoveryService.buildRegionQueryTargets(
                    responders = setOf(publicKey),
                    contacts = listOf(stored),
                    discoveredNodes = emptyList(),
                    supportsAdHocRequest = false,
                ).single()
                val request = async(Dispatchers.Default) {
                    try {
                        session.requestRegions(contact)
                        null
                    } catch (failure: MeshBridgeException) {
                        failure
                    }
                }
                val frame = withTimeout(MeshCoreSessionInstrumentedTest.TIMEOUT_MS) {
                    var next = transport.awaitSend()
                    while (next.firstOrNull() != ANON_REQUEST_CODE) next = transport.awaitSend()
                    next
                }
                val expected = byteArrayOf(ANON_REQUEST_CODE) + publicKey +
                    byteArrayOf(REGIONS_REQUEST_TYPE, case.length.toByte()) + case.replyPath
                assertTrue("encoded path ${case.length}", expected.contentEquals(frame))

                session.deliverReceived(byteArrayOf(ERROR_RESPONSE_CODE, DEVICE_ERROR_CODE))
                val failure = withTimeout(MeshCoreSessionInstrumentedTest.TIMEOUT_MS) { request.await() }
                assertEquals(BridgeErrorCode.DEVICE_ERROR, failure?.code)
            }
        }
    }

    private data class ReplyPath(val length: Int, val outPath: ByteArray, val replyPath: ByteArray)

    private companion object {
        const val ANON_REQUEST_CODE: Byte = 0x39
        const val REGIONS_REQUEST_TYPE: Byte = 0x01
        const val ERROR_RESPONSE_CODE: Byte = 0x01
        const val DEVICE_ERROR_CODE: Byte = 10
    }
}
