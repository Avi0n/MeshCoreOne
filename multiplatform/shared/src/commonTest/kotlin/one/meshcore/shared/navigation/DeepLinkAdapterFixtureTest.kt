package one.meshcore.shared.navigation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.remotenode.MAX_REGION_NAME_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deep links enter through [PendingExternalUrl] and [DeepLinkRouter.routeExternalOpen]. A send is
 * enqueued only by [enqueueSendMessageShortcut], after the length and radio checks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeepLinkAdapterFixtureTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val otherRadio = Uuid.normalized("B0000000-0000-0000-0000-000000000002")
    private val contactId = Uuid.normalized("C0000000-0000-0000-0000-000000000003")
    private val channelId = Uuid.normalized("D0000000-0000-0000-0000-000000000004")

    @Test
    fun malformedUrlsRestoreThePreviousTabAndSkipLookups() = runTest {
        val urls = listOf(
            "meshcore://contact/add?name=Ada&public_key=%ZZ",
            "meshcore://map?lat=999&lon=0",
            "meshcore://contact/add?name=Ada&public_key=${"ab".repeat(MeshCoreUrlParser.PUBLIC_KEY_SIZE_BYTES + 1)}",
        )
        for (url in urls) {
            val routed = routeHeld(url)
            assertFalse(routed.handled)
            assertEquals(AppTab.MAP.index, routed.nav.selectedTab.value)
            assertEquals(0, routed.lookups.calls)
            assertNull(routed.nav.pendingContactLink.value)
            assertNull(routed.nav.pendingChannelLink.value)
            assertNull(routed.nav.pendingSendMessage.value)
        }
    }

    @Test
    fun oversizedChannelTextIsRejectedBeforeEnqueue() = runTest {
        val nodeName = "RepeaterOne"
        val budget = channelShortcutBudgetBytes(nodeName)
        val text = "x".repeat(budget + 1)
        val url = "meshcore://send?kind=channel&id=${channelId.value}&text=$text"
        val routed = routeHeld(url)
        assertTrue(routed.handled)
        val staged = assertNotNull(routed.nav.pendingSendMessage.value)
        assertEquals(text, staged.text)
        assertEquals(0, routed.lookups.calls)
        var enqueued = false
        val outcome = enqueueSendMessageShortcut(
            ShortcutEnqueue(
                request = staged,
                radioReady = true,
                currentRadioId = radio,
                fetchContact = { null },
                fetchChannel = { privateChannel() },
                enqueueDm = { enqueued = true },
                enqueueChannel = { enqueued = true },
                localNodeName = nodeName,
            ),
        )
        assertEquals(SendMessageShortcutOutcome.MessageTooLong, outcome)
        assertFalse(enqueued)
        assertEquals(PRIVATE_CHANNEL_INDEX, privateChannel().index)
    }

    @Test
    fun oversizedRegionScopeIsCappedBeforeTheJoinIsStaged() = runTest {
        val secret = ByteArray(MeshCoreUrlParser.CHANNEL_SECRET_SIZE_BYTES) { SECRET_FILL.toByte() }.toUppercaseHex()
        val region = "R".repeat(MAX_REGION_NAME_BYTES + REGION_OVERSHOOT_BYTES)
        val url = "meshcore://channel/add?name=Ops&secret=$secret&region_scope=$region"
        val routed = routeHeld(url)
        assertTrue(routed.handled)
        val staged = assertNotNull(routed.nav.pendingChannelLink.value)
        assertEquals(MAX_REGION_NAME_BYTES, staged.regionScope?.encodeToByteArray()?.size)
        assertNull(routed.nav.pendingSendMessage.value)
    }

    @Test
    fun aContactOnAnotherRadioIsNotEnqueued() = runTest {
        val url = "meshcore://send?kind=contact&id=${contactId.value}&text=hello"
        val routed = routeHeld(url)
        assertTrue(routed.handled)
        val staged = assertNotNull(routed.nav.pendingSendMessage.value)
        assertNull(resolveContactTarget(staged.targetId, emptyList()))
        var enqueued = false
        val outcome = enqueueSendMessageShortcut(
            ShortcutEnqueue(
                request = staged,
                radioReady = true,
                currentRadioId = radio,
                fetchContact = { contact().copy(radioID = otherRadio) },
                fetchChannel = { null },
                enqueueDm = { enqueued = true },
                enqueueChannel = { enqueued = true },
                localNodeName = null,
            ),
        )
        assertEquals(SendMessageShortcutOutcome.MustForeground, outcome)
        assertFalse(enqueued)
    }

    @Test
    fun aLaterUrlReplacesOneSubmittedBeforeReady() = runTest {
        val nav = NavigationCoordinator(backgroundScope)
        nav.selectTab(AppTab.MAP)
        val router = DeepLinkRouter(nav, CountingLookups())
        val pending = PendingExternalUrl()
        val routed = mutableListOf<String>()
        pending.submit("meshcore://map?lat=999&lon=0")
        pending.submit("meshcore://map?lat=1.5&lon=-2.25")
        pending.markReady { url ->
            launch {
                routed += url
                router.routeExternalOpen(url)
            }
        }
        advanceUntilIdle()
        assertEquals(listOf("meshcore://map?lat=1.5&lon=-2.25"), routed)
        assertEquals(MapFocusRequest(1.5, -2.25), nav.pendingMapFocus.value)
        assertEquals(AppTab.MAP.index, nav.selectedTab.value)
    }

    @Test
    fun aCancelledContactLookupStagesNothing() = runTest {
        val nav = NavigationCoordinator(backgroundScope)
        nav.selectTab(AppTab.MAP)
        val lookups = HoldingLookups()
        val router = DeepLinkRouter(nav, lookups)
        val key = ByteArray(MeshCoreUrlParser.PUBLIC_KEY_SIZE_BYTES) { CONTACT_KEY_FILL.toByte() }.toUppercaseHex()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            router.routeExternalOpen("meshcore://contact/add?name=Ada&public_key=$key&type=1")
        }
        assertTrue(lookups.entered)
        job.cancel()
        advanceUntilIdle()
        assertNull(nav.pendingContactLink.value)
        assertNull(nav.pendingSendMessage.value)
        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
    }

    private suspend fun TestScope.routeHeld(url: String): Routed {
        val lookups = CountingLookups()
        val nav = NavigationCoordinator(backgroundScope)
        nav.selectTab(AppTab.MAP)
        val router = DeepLinkRouter(nav, lookups)
        val pending = PendingExternalUrl()
        var handled = false
        val scope = this
        pending.markReady { incoming ->
            scope.launch {
                handled = router.routeExternalOpen(incoming)
            }
        }
        pending.submit(url)
        advanceUntilIdle()
        return Routed(handled, nav, lookups)
    }

    private fun privateChannel() = ChannelDto(
        id = channelId,
        radioID = radio,
        index = PRIVATE_CHANNEL_INDEX,
        name = "Ops",
        secret = ByteArray(MeshCoreUrlParser.CHANNEL_SECRET_SIZE_BYTES),
        isEnabled = true,
        unreadCount = 0,
    )

    private fun contact() = ContactDto(
        id = contactId,
        radioID = radio,
        publicKey = ByteArray(MeshCoreUrlParser.PUBLIC_KEY_SIZE_BYTES),
        name = "Ada",
        typeRawValue = ContactType.CHAT.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )

    private class Routed(val handled: Boolean, val nav: NavigationCoordinator, val lookups: CountingLookups)

    private class CountingLookups : DeepLinkLookups {
        var calls = 0

        override suspend fun ownPublicKey(): ByteArray? {
            calls += 1
            return null
        }

        override suspend fun existingContactKeyHexes(): Set<String> {
            calls += 1
            return emptySet()
        }

        override suspend fun existingChannelSecretHexes(): Set<String> {
            calls += 1
            return emptySet()
        }
    }

    private class HoldingLookups : DeepLinkLookups {
        var entered = false

        override suspend fun ownPublicKey(): ByteArray? = suspendCancellableCoroutine {
            entered = true
        }

        override suspend fun existingContactKeyHexes(): Set<String> = emptySet()

        override suspend fun existingChannelSecretHexes(): Set<String> = emptySet()
    }

    private companion object {
        const val PRIVATE_CHANNEL_INDEX = 1
        const val SECRET_FILL = 0x44
        const val CONTACT_KEY_FILL = 0x22
        const val REGION_OVERSHOOT_BYTES = 8
    }
}
