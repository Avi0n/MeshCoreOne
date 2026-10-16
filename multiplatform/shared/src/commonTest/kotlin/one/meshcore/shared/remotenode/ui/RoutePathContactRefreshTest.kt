package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.CONTACT_FLOOD_PATH_SENTINEL
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RoutePathContactRefreshTest {
    private val radio = Uuid.normalized("B0000000-0000-0000-0000-000000000010")
    private val key = ByteArray(32) { 0x42 }

    @Test
    fun adoptsAFetchedContact() = runBlocking {
        val seed = contact(outPathLength = CONTACT_FLOOD_PATH_SENTINEL)
        val learned = contact(outPathLength = 2, outPath = byteArrayOf(0x11, 0x22))

        val next = refreshRoutePathContact(seed, radio, key) { _, _ -> learned }

        assertEquals(2, next.outPathLength)
        assertEquals(listOf(0x11.toByte(), 0x22.toByte()), next.outPath.toList())
    }

    @Test
    fun keepsTheSeedWhenFetchThrows() = runBlocking {
        val seed = contact(outPathLength = CONTACT_FLOOD_PATH_SENTINEL)

        val next = refreshRoutePathContact(seed, radio, key) { _, _ -> error("disk full") }

        assertSame(seed, next)
    }

    @Test
    fun keepsTheSeedWhenFetchMisses() = runBlocking {
        val seed = contact(outPathLength = CONTACT_FLOOD_PATH_SENTINEL)

        val next = refreshRoutePathContact(seed, radio, key) { _, _ -> null }

        assertSame(seed, next)
    }

    @Test
    fun fetchUsesTheSessionRadioAndKey() = runBlocking {
        val seed = contact(outPathLength = CONTACT_FLOOD_PATH_SENTINEL)
        var seenRadio: Uuid? = null
        var seenKey: ByteArray? = null

        refreshRoutePathContact(seed, radio, key) { r, k ->
            seenRadio = r
            seenKey = k
            null
        }

        assertEquals(radio, seenRadio)
        assertEquals(key.toList(), seenKey?.toList())
    }

    @Test
    fun aCancelledFetchPropagates() = runTest {
        val seed = contact(outPathLength = CONTACT_FLOOD_PATH_SENTINEL)
        val cancelled = CancellationException("superseded")
        val thrown = assertFailsWith<CancellationException> {
            refreshRoutePathContact(seed, radio, key) { _, _ -> throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun aFetchErrorPropagates() = runTest {
        val seed = contact(outPathLength = CONTACT_FLOOD_PATH_SENTINEL)
        val fault = RoutePathProbeError()
        val thrown = assertFailsWith<RoutePathProbeError> {
            refreshRoutePathContact(seed, radio, key) { _, _ -> throw fault }
        }
        assertSame(fault, thrown)
    }

    private fun contact(outPathLength: Int, outPath: ByteArray = ByteArray(0)): ContactDto = ContactDto(
        id = Uuid.normalized("A0000000-0000-0000-0000-000000000010"),
        radioID = radio,
        publicKey = key,
        name = "Repeater",
        typeRawValue = ContactType.REPEATER.raw,
        flags = 0,
        outPathLength = outPathLength,
        outPath = outPath,
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )
}

private class RoutePathProbeError : Error("probe")
