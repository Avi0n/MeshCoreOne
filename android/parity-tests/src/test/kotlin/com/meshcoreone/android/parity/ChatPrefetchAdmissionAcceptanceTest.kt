// PortedFrom: MC1Tests/Views/Chats/ChatViewModelAdmissionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-501 Mounted App event/Room/content-cache counterparts; no duplicate original-family credit.
package com.meshcoreone.android.parity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.content.DataStoreLinkPreviewPreferencesSource
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.content.*
import com.meshcoreone.android.feature.chats.composer.LinkPreviewOutcome
import com.meshcoreone.android.feature.chats.composer.LinkPreviewStateHolder
import com.meshcoreone.android.feature.chats.composer.LinkPreviewCardState
import com.meshcoreone.android.feature.chats.composer.UrlSafetyPort
import com.meshcoreone.android.feature.chats.composer.MalwareDomainPort
import com.meshcoreone.android.feature.chats.timeline.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPrefetchAdmissionAcceptanceTest : RoomProcessTest() {
    @Test fun plainTextAdmitsImmediatelyWithoutEitherContentCollaborator() = runTest {
        withApp { f ->
            val message = f.message("hello world, nothing to fetch")
            f.emit(message)
            f.awaitMessage(message)
            assertTrue(f.http.requests.isEmpty())
            assertEquals(listOf(message), f.viewModel.state.value.messages)
        }
    }

    @Test fun masterOffUrlAdmitsWithoutImagePreviewOrSafetySideEffects() = runTest {
        withApp(enabled = false) { f ->
            val message = f.message("see $CAT")
            f.emit(message)
            f.awaitMessage(message)
            assertTrue(f.http.requests.isEmpty())
            assertTrue(f.safetyChecks.isEmpty())
        }
    }

    @Test fun fastUrlProbeCompletesBeforeAdmissionThroughTheRealProcessImageCache() = runTest {
        withApp { f ->
            f.http.probeDelay = 50
            val message = f.message("see $CAT")
            f.emit(message)
            f.awaitProbe()
            assertTrue(f.viewModel.state.value.messages.isEmpty())
            advanceTimeBy(49); runCurrent()
            assertTrue(f.viewModel.state.value.messages.isEmpty())
            advanceTimeBy(1); runCurrent()
            f.awaitMessage(message)
            assertEquals(listOf(CAT), f.http.probes)
            assertEquals(listOf(CAT), f.safetyChecks)
        }
    }

    @Test fun absentOptionalContentSeamRetainsTheImmediateAdmissionFastPath() = runTest {
        withApp(wireContent = false) { f ->
            assertNull(f.harness.container.chatsFeature.timeline(f.conversation).content)
            val message = f.message(CAT)
            f.emit(message)
            f.awaitMessage(message)
            assertTrue(f.http.requests.isEmpty())
        }
    }

    @Test fun real200By100HeaderIsAvailableToTheMatchingNativeMessageRowAtAdmission() = runTest {
        withApp { f ->
            val message = f.message("look $CAT")
            f.emit(message)
            f.awaitMessage(message)
            val content = assertNotNull(f.content)
            val media = content.media(f.viewModel.state.value.messages.single().text).single()
            assertEquals(CAT, media.url)
            assertTrue(media.isImage)
            assertEquals(2.0, media.aspect)
            assertEquals(2.0, content.aspect(CAT))
            assertTrue(content.resolutions.value > 0)
            assertEquals(2.0, com.meshcoreone.android.app.content.inlineImageDimensionsStore(f.context).aspect(CAT),
                "Reopening the actual file store must retain the probed dimensions")
            assertEquals(message, store.fetchMessage(EntityKey(f.radio, message.id)))
        }
    }

    @Test fun lateDimensionsStreamRebuildsTheAdmittedMatchingRowBeforePrefetchCompletes() = runTest {
        withApp(autoDM = false) { f ->
            val matching = f.message("look $CAT")
            val other = f.message("other https://example.com/dog.png")
            f.emit(matching)
            f.awaitMessage(matching)
            f.emit(other)
            f.awaitMessage(other)
            val content = assertNotNull(f.content)
            fun media(message: MessageDTO) =
                content.media(f.viewModel.state.value.messages.single { it.id == message.id }.text).single()
            assertEquals(CAT, media(matching).url)
            assertTrue(media(matching).isImage)
            assertNull(media(matching).aspect)
            assertNull(media(other).aspect)
            assertTrue(f.http.requests.isEmpty())

            f.storage.preferences.set(AppStorageKey.linkPreviewsAutoResolveDM, true)
            f.harness.eventually("automatic probes enabled", virtualBudgetMillis = 0) {
                content.rendering.value[AppStorageKey.linkPreviewsAutoResolveDM]
            }
            val beforeResolution = content.resolutions.value
            val updated = f.screenScope.async(start = CoroutineStart.UNDISPATCHED) {
                val revision = content.resolutions.first { it > beforeResolution }
                revision to media(matching)
            }
            val held = "https://example.com/held.png"
            f.http.heldProbeUrl = held
            val prefetch = f.screenScope.async { content.prefetch("$CAT $held", isChannelMessage = false) }
            try {
                f.harness.eventually("post-admission dimensions stream", virtualBudgetMillis = 0) {
                    updated.isCompleted && f.http.completedProbes == 1 && held in f.http.probes
                }
                assertFalse(prefetch.isCompleted, "Completion invalidation must still be unreachable")
                assertFalse(f.http.releaseHeldProbe.isCompleted)
                val (revision, rebuilt) = updated.await()
                assertTrue(revision > beforeResolution)
                assertEquals(CAT, rebuilt.url)
                assertTrue(rebuilt.isImage)
                assertEquals(2.0, rebuilt.aspect)
                assertEquals(rebuilt, media(matching))
                assertNull(media(other).aspect, "A different admitted row must not acquire the resolved aspect")
                assertEquals(listOf(matching, other), f.viewModel.state.value.messages)
                assertEquals(matching, store.fetchMessage(EntityKey(f.radio, matching.id)))
                assertEquals(2.0, com.meshcoreone.android.app.content.inlineImageDimensionsStore(f.context).aspect(CAT))
                f.http.releaseHeldProbe.complete(Unit)
                prefetch.await()
            } finally {
                withContext(NonCancellable) {
                    prefetch.cancelAndJoin()
                    updated.cancelAndJoin()
                }
            }
        }
    }

    @Test fun fiveSecondProbeLosesTheInjected200msRaceAndCannotWriteDimensionsLate() = runTest {
        withApp { f ->
            f.http.probeDelay = 5_000
            val message = f.message(CAT)
            f.emit(message)
            f.awaitProbe()
            val started = testScheduler.currentTime
            advanceTimeBy(200); runCurrent()
            f.awaitMessage(message)
            assertTrue(testScheduler.currentTime - started < 1_000)
            assertEquals(1, f.http.cancelledProbes)
            val content = assertNotNull(f.content)
            assertNull(content.aspect(CAT))
            advanceTimeBy(5_000); runCurrent()
            assertNull(content.aspect(CAT))
            assertEquals(listOf(message), f.viewModel.state.value.messages)
        }
    }

    @Test fun giphyShortcodeUsesTheFrozenIndependentDirectGifLiteral() = runTest {
        withApp { f ->
            f.http.probeDelay = 5
            val message = f.message("g:abc123")
            f.emit(message)
            f.awaitProbe()
            advanceTimeBy(5); runCurrent()
            f.awaitMessage(message)
            assertEquals(listOf("https://media.giphy.com/media/abc123/giphy.gif"), f.http.probes)
            assertEquals(2.0, assertNotNull(f.content).media(message.text).single().aspect)
        }
    }

    @Test fun productionDefaultIsThreeSecondsRatherThanTheInjectedShortProbeDeadline() = runTest {
        withApp(timeoutMillis = null) { f ->
            f.http.probeDelay = 5_000
            val message = f.message(CAT)
            f.emit(message)
            f.awaitProbe()
            advanceTimeBy(2_999); runCurrent()
            assertTrue(f.viewModel.state.value.messages.isEmpty())
            advanceTimeBy(1); runCurrent()
            f.awaitMessage(message)
            assertEquals(1, f.http.cancelledProbes)
        }
    }

    @Test fun timedOutAdmissionLeavesTheRealPreviewWorkerAliveForLiveCardStateAndRoomReentry() = runTest {
        withApp { f ->
            val page = "https://8.8.8.8/article"
            f.http.htmlUrl = page
            f.http.pageDelay = 5_000
            val message = f.message(page)
            f.emit(message)
            f.harness.eventually("actual metadata HTTP", virtualBudgetMillis = 0) { page in f.http.requests }
            advanceTimeBy(200); runCurrent()
            f.awaitMessage(message)
            assertNull(store.fetchLinkPreview(page))
            val content = assertNotNull(f.content)
            val holder = LinkPreviewStateHolder(
                page, false, content.previews(false), UrlSafetyPort { UrlSafetyChecker.isSafe(it) },
                MalwareDomainPort(content.malware::isBlocked), f.screenScope,
            )
            try {
                holder.start()
                f.harness.eventually("native card loading", virtualBudgetMillis = 0) {
                    holder.state.value is LinkPreviewCardState.Loading
                }
                advanceTimeBy(4_800); runCurrent()
                f.harness.eventually("actual late card completion", virtualBudgetMillis = 0) {
                    holder.state.value is LinkPreviewCardState.Loaded
                }
                assertEquals("Pasteboard", assertIs<LinkPreviewCardState.Loaded>(holder.state.value).data.title)
                assertEquals("Pasteboard", assertNotNull(store.fetchLinkPreview(page)).title)
                f.screenJob.cancelAndJoin()
                assertIs<LinkPreviewOutcome.Loaded>(content.previews(false).preview(page, false))
                assertFalse(content.media(message.text).single().isImage)
            } finally { holder.close() }
        }
    }

    @Test fun knownHtmlImageReentryUsesRealPersistedLoadedCardInsteadOfAnInlineShimmer() = runTest {
        withApp { f ->
            val page = "https://pasteboard.co/10000000-0000-0000-0000-000000000001.png"
            f.http.htmlUrl = page
            val content = assertNotNull(f.content)
            assertEquals(ChatImage.HtmlPage, content.inline(page))
            store.saveLinkPreview(LinkPreviewDataDTO(url = page, title = "Pasteboard"))
            val message = f.message(page)
            f.emit(message)
            f.awaitMessage(message)
            assertFalse(content.media(message.text).single().isImage)
            val first = assertIs<LinkPreviewOutcome.Loaded>(content.previews(false).preview(page, false))
            assertEquals("Pasteboard", first.data.title)
            val count = f.http.requests.size
            val reentered = f.newViewModel()
            reentered.open()
            assertEquals(listOf(message), reentered.state.value.messages)
            assertFalse(content.media(reentered.state.value.messages.single().text).single().isImage)
            assertIs<LinkPreviewOutcome.Loaded>(content.previews(false).preview(page, false))
            assertEquals(count, f.http.requests.size, "Reentry must consume the same process and Room cache")
        }
    }

    @Test fun actualPendingSendIsVisibleImmediatelyWhileItsProbeRunsAsynchronously() = runTest {
        withApp { f ->
            f.http.probeDelay = 10
            val message = f.harness.container.chatsFeature.sender(f.radio).createPendingDirect(
                "outgoing https://example.com/dog.png", f.contact, TextType.PLAIN, null,
            )
            f.awaitProbe()
            f.awaitMessage(message)
            assertEquals(MessageStatus.PENDING, message.status)
            assertEquals(MessageDirection.OUTGOING, message.direction)
            assertFalse(f.http.completedProbes > 0)
            advanceTimeBy(10); runCurrent()
            assertEquals(listOf("https://example.com/dog.png"), f.http.probes)
            assertEquals(1, f.http.completedProbes)
            assertEquals(message, store.fetchMessage(EntityKey(f.radio, message.id)))
        }
    }

    @Test fun screenCancellationAndRadioReplacementNeverAdmitTheHeldOldConversation() = runTest {
        withApp { f ->
            f.http.probeDelay = 5_000
            val message = f.message(CAT)
            f.emit(message)
            f.awaitProbe()
            f.screenJob.cancelAndJoin()
            advanceTimeBy(5_000); runCurrent()
            assertTrue(f.viewModel.state.value.messages.isEmpty())
            assertEquals(1, f.http.cancelledProbes)
            assertNull(assertNotNull(f.content).aspect(CAT))
            assertEquals(message, store.fetchMessage(EntityKey(f.radio, message.id)))
        }
        withApp { f ->
            f.http.probeDelay = 5_000
            val message = f.message(CAT)
            f.emit(message)
            f.awaitProbe()
            f.harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            advanceTimeBy(200); runCurrent()
            assertTrue(f.viewModel.state.value.messages.isEmpty())
            assertEquals(message, store.fetchMessage(EntityKey(f.radio, message.id)))
        }
    }

    @Test fun privacyGatesImagesAndCardsSeparatelyForDirectAndChannelWithoutRadioCommands() = runTest {
        withApp(autoDM = false, autoChannel = true) { f ->
            val message = f.message("see $CAT and https://example.com/article")
            f.emit(message)
            f.awaitMessage(message)
            assertTrue(f.http.requests.isEmpty())
            assertTrue(f.safetyChecks.isEmpty())
            val channel = ChannelDTO(radioId = f.radio, index = 0u, name = "Test")
            store.saveChannel(channel)
            val conversation = TimelineConversation.Channel(channel)
            val timeline = ChatViewModel(conversation, f.harness.container.chatsFeature.timeline(conversation), f.screenScope)
            timeline.start(); runCurrent()
            val incoming = f.message(CAT).copy(contactID = null, channelIndex = 0u)
            store.saveMessage(incoming)
            val beforeWire = f.harness.links.radios.single().sent.toList()
            f.harness.appState.messageEventStream.send(MessageEvent.ChannelMessageReceived(incoming, 0u))
            f.harness.eventually("channel admission", virtualBudgetMillis = 0) {
                timeline.state.value.messages.any { it.id == incoming.id }
            }
            assertEquals(listOf(CAT), f.http.probes)
            assertEquals(beforeWire, f.harness.links.radios.single().sent)
        }
        withApp(autoDM = true, autoChannel = false) { f ->
            val channel = ChannelDTO(radioId = f.radio, index = 0u, name = "Test")
            store.saveChannel(channel)
            val conversation = TimelineConversation.Channel(channel)
            val timeline = ChatViewModel(conversation, f.harness.container.chatsFeature.timeline(conversation), f.screenScope)
            timeline.start(); runCurrent()
            val incoming = f.message("$CAT https://example.com/article").copy(contactID = null, channelIndex = 0u)
            store.saveMessage(incoming)
            f.harness.appState.messageEventStream.send(MessageEvent.ChannelMessageReceived(incoming, 0u))
            f.harness.eventually("private channel admission", virtualBudgetMillis = 0) {
                timeline.state.value.messages.any { it.id == incoming.id }
            }
            assertTrue(f.http.requests.isEmpty())
            assertTrue(f.safetyChecks.isEmpty())
            val direct = f.message(CAT)
            f.emit(direct)
            f.awaitMessage(direct)
            assertEquals(listOf(CAT), f.http.probes)
        }
    }

    @Test fun actualProtocolPollingPersistsThenPrefetchesBeforeTheMountedDirectRowAppears() = runTest {
        withApp { f ->
            f.http.probeDelay = 50
            val firmware = f.harness.links.radios.single()
            val frame = Bytes.of(0x10, 20, 0, 0) + f.contact.publicKey.prefix(6) + Bytes.of(0, 0) +
                Bytes.fromHex("e8030000") + Bytes.utf8("see $CAT")
            val frames = ConcurrentLinkedQueue(listOf(frame))
            firmware.respond = { command ->
                if (command[0] == 0x0a.toUByte()) listOf(frames.poll() ?: Bytes.of(0x0a)) else null
            }
            assertEquals(1L, assertNotNull(f.harness.sessions.current).messagePollingService.pollAllMessages())
            f.awaitProbe()
            val persisted = store.fetchMessages(EntityKey(f.radio, f.contact.id)).single()
            assertEquals("see $CAT", persisted.text)
            assertTrue(f.viewModel.state.value.messages.isEmpty())
            advanceTimeBy(50); runCurrent()
            f.awaitMessage(persisted)
            assertEquals(2.0, assertNotNull(f.content).media(persisted.text).single().aspect)
            assertTrue(firmware.sent.none { it[0] == 0x36.toUByte() },
                "Prefetch and a direct-message consumer must not set radio flood scope")
        }
    }

    private suspend fun TestScope.withApp(
        enabled: Boolean = true, wireContent: Boolean = true,
        autoDM: Boolean = true, autoChannel: Boolean = true,
        timeoutMillis: Long? = 200,
        body: suspend (Fixture) -> Unit,
    ) {
        val f = Fixture(this)
        try {
            f.timeoutMillis = timeoutMillis
            f.start(enabled, wireContent, autoDM, autoChannel)
            body(f)
        } finally {
            withContext(NonCancellable) {
                f.screenJob.cancelAndJoin()
                f.close()
            }
        }
    }

    private inner class Fixture(val test: TestScope) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        val http = FixtureHttp()
        val safetyChecks = mutableListOf<String>()
        val contentJob = SupervisorJob(test.backgroundScope.coroutineContext[Job])
        private val contentScope = CoroutineScope(test.backgroundScope.coroutineContext + contentJob)
        val screenJob = SupervisorJob(test.backgroundScope.coroutineContext[Job])
        val screenScope = CoroutineScope(test.backgroundScope.coroutineContext + screenJob)
        var content: AppChatContent? = null
        var timeoutMillis: Long? = 200
        lateinit var harness: ContainerHarness
        lateinit var contact: ContactDTO
        val radio get() = contact.radioId
        val conversation get() = TimelineConversation.Direct(contact)
        lateinit var viewModel: ChatViewModel

        suspend fun start(enabled: Boolean, wireContent: Boolean, autoDM: Boolean, autoChannel: Boolean) {
            context.getFileStreamPath("InlineImageDimensions.json").delete()
            storage.preferences.set(AppStorageKey.linkPreviewsEnabled, enabled)
            storage.preferences.set(AppStorageKey.linkPreviewsAutoResolveDM, autoDM)
            storage.preferences.set(AppStorageKey.linkPreviewsAutoResolveChannels, autoChannel)
            if (wireContent) content = AppChatContent(
                context, store, http, DataStoreLinkPreviewPreferencesSource.create(storage.preferences, contentScope),
                storage.preferences, storage.preferences.snapshot(), contentScope,
                imageSafety = { url -> safetyChecks += url; true },
            )
            harness = ContainerHarness(test, store, chatContent = content)
            harness.manager.connect(harness.target())
            harness.eventually("ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            contact = ContactDTO(
                radioId = assertNotNull(harness.sessions.current).token.radioId,
                publicKey = Bytes(ByteArray(32) { 3 }), name = "Peer", lastHeardTimestamp = null,
            )
            store.saveContact(contact)
            viewModel = newViewModel()
            viewModel.start()
            test.runCurrent()
        }
        fun newViewModel(): ChatViewModel {
            val dependencies = harness.container.chatsFeature.timeline(conversation)
            val timeout = timeoutMillis
            return if (timeout == null) ChatViewModel(conversation, dependencies, screenScope)
                else ChatViewModel(conversation, dependencies, screenScope, prefetchTimeoutMillis = timeout)
        }
        fun message(text: String) = MessageDTO(
            radioId = radio, contactID = contact.id, text = text, timestamp = 1_000u,
            createdAt = Instant.ofEpochSecond(1_000), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED,
        )
        suspend fun emit(message: MessageDTO) {
            store.saveMessage(message)
            harness.appState.messageEventStream.send(MessageEvent.DirectMessageReceived(message, contact))
        }
        fun awaitMessage(message: MessageDTO) = harness.eventually("admitted row", virtualBudgetMillis = 0) {
            viewModel.state.value.messages.any { it.id == message.id }
        }
        fun awaitProbe() = harness.eventually("actual Range probe", virtualBudgetMillis = 0) { http.probes.isNotEmpty() }
        suspend fun close() {
            try {
                if (this::harness.isInitialized) harness.container.close()
            } finally {
                content?.close()
                contentJob.cancelAndJoin()
                storage.close()
            }
        }
    }

    private class FixtureHttp : BoundedHttpFetching {
        val requests = mutableListOf<String>()
        val probes = mutableListOf<String>()
        var probeDelay = 0L
        var completedProbes = 0
        var cancelledProbes = 0
        var heldProbeUrl: String? = null
        val releaseHeldProbe = CompletableDeferred<Unit>()
        var htmlUrl: String? = null
        var pageDelay = 0L
        override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
            requests += url
            if (rangeHeader != null) {
                assertEquals("bytes=0-65535", rangeHeader)
                probes += url
                try {
                    if (url == heldProbeUrl) releaseHeldProbe.await()
                    delay(probeDelay)
                }
                catch (cancelled: CancellationException) { cancelledProbes++; throw cancelled }
                completedProbes++
            } else {
                if (url != htmlUrl) return HttpFetchAttempt.Failed("No external network in the fixture")
                delay(pageDelay)
            }
            val html = url == htmlUrl
            val bytes = if (html) "<html><meta property=\"og:title\" content=\"Pasteboard\"></html>".toByteArray(Charsets.UTF_8)
                else Bytes.fromHex("89504e470d0a1a0a0000000d49484452000000c800000064").toByteArray()
            return HttpFetchAttempt.Started(200, if (html) "text/html" else "image/png",
                bytes.size.toLong(), {}) { chunk -> chunk(bytes); Unit }
        }
    }
    private companion object { const val CAT = "https://example.com/cat.png" }
}
