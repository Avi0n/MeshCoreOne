package one.meshcore.shared.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatDisplayPreferencesTest {
    @Test
    fun defaultsWhenEmpty() = runTest {
        val store = InMemoryPreferencesStore()
        val prefs = store.loadChatDisplayPreferences()
        assertFalse(prefs.showIncomingPath)
        assertFalse(prefs.showIncomingHopCount)
        assertFalse(prefs.showIncomingRegion)
        assertFalse(prefs.showIncomingHeardCount)
        assertFalse(prefs.showIncomingSendTime)
        assertFalse(prefs.replyWithQuote)
        assertFalse(prefs.linkPreviewsEnabled)
        assertTrue(prefs.linkPreviewsAutoResolveDM)
        assertTrue(prefs.linkPreviewsAutoResolveChannels)
        assertTrue(prefs.autoPlayGIFs)
        assertTrue(prefs.showMapPreviewThumbnails)
    }

    @Test
    fun linkPreviewScopeTogglesRoundTripUnderTheSwiftKeys() = runTest {
        val store = InMemoryPreferencesStore()
        store.setLinkPreviewsAutoResolveDM(false)
        store.setLinkPreviewsAutoResolveChannels(false)
        val prefs = store.loadChatDisplayPreferences()
        assertFalse(prefs.linkPreviewsAutoResolveDM)
        assertFalse(prefs.linkPreviewsAutoResolveChannels)
        assertEquals(false, store.getBoolean("linkPreviewsAutoResolveDM"))
        assertEquals(false, store.getBoolean("linkPreviewsAutoResolveChannels"))
    }

    @Test
    fun autoPlayGifsRoundTrips() = runTest {
        val store = InMemoryPreferencesStore()
        store.setAutoPlayGIFs(false)
        assertFalse(store.loadChatDisplayPreferences().autoPlayGIFs)
        assertEquals(false, store.getBoolean(PersistenceKeys.AUTO_PLAY_GIFS))
    }

    @Test
    fun replyWithQuoteRoundTrips() = runTest {
        val store = InMemoryPreferencesStore()
        store.setReplyWithQuote(true)
        assertTrue(store.loadChatDisplayPreferences().replyWithQuote)
        assertEquals(true, store.getBoolean(PersistenceKeys.REPLY_WITH_QUOTE))
    }

    @Test
    fun roundTripWrites() = runTest {
        val store = InMemoryPreferencesStore()
        store.setShowIncomingPath(true)
        store.setShowIncomingHopCount(true)
        store.setShowIncomingHeardCount(true)
        store.setLinkPreviewsEnabled(true)
        store.setShowMapPreviewThumbnails(false)
        val prefs = store.loadChatDisplayPreferences()
        assertTrue(prefs.showIncomingPath)
        assertTrue(prefs.showIncomingHopCount)
        assertTrue(prefs.showIncomingHeardCount)
        assertEquals(true, store.getBoolean(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT))
        assertTrue(prefs.linkPreviewsEnabled)
        assertFalse(prefs.showMapPreviewThumbnails)
        assertEquals(true, store.getBoolean(PersistenceKeys.SHOW_INCOMING_PATH))
        assertEquals(true, store.getBoolean(PersistenceKeys.LINK_PREVIEWS_ENABLED))
        assertEquals(false, store.getBoolean(PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepingTheIncomingRegionLabelOffDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertTrue(prefs.snapshot().isEmpty())

        store.setShowIncomingRegion(false)
        advanceUntilIdle()

        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertEquals(false, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_REGION))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_INCOMING_REGION to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun showingTheIncomingRegionLabelDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.showIncomingRegion)

        store.setShowIncomingRegion(true)
        assertTrue(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHeardCount)
        advanceUntilIdle()

        assertEquals(true, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_REGION))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_INCOMING_REGION to true),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepingTheIncomingTimeLabelOffDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.showIncomingSendTime)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertTrue(prefs.snapshot().isEmpty())

        store.setShowIncomingSendTime(false)
        advanceUntilIdle()

        assertFalse(store.state.value.showIncomingSendTime)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertEquals(false, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_SEND_TIME))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_INCOMING_SEND_TIME to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun showingTheIncomingSendTimeDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.showIncomingSendTime)

        store.setShowIncomingSendTime(true)
        assertTrue(store.state.value.showIncomingSendTime)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHeardCount)
        advanceUntilIdle()

        assertEquals(true, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_SEND_TIME))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_INCOMING_SEND_TIME to true),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepingTheIncomingHopCountOffDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertTrue(prefs.snapshot().isEmpty())

        store.setShowIncomingHopCount(false)
        advanceUntilIdle()

        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertEquals(false, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_HOP_COUNT))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_REGION))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_PATH))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_INCOMING_HOP_COUNT to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun showingTheIncomingHopCountDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.showIncomingHopCount)

        store.setShowIncomingHopCount(true)
        assertTrue(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertFalse(store.state.value.showIncomingSendTime)
        advanceUntilIdle()

        assertEquals(true, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_HOP_COUNT))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_INCOMING_HOP_COUNT to true),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepingTheIncomingHeardCountOffDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertTrue(prefs.snapshot().isEmpty())

        store.setShowIncomingHeardCount(false)
        advanceUntilIdle()

        assertFalse(store.state.value.showIncomingHeardCount)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertEquals(false, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_HOP_COUNT))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_REGION))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_PATH))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun showingTheIncomingHeardCountDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.showIncomingHeardCount)

        store.setShowIncomingHeardCount(true)
        assertTrue(store.state.value.showIncomingHeardCount)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingSendTime)
        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.linkPreviewsEnabled)
        advanceUntilIdle()

        assertEquals(true, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT to true),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepingTheIncomingPathOffDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertTrue(prefs.snapshot().isEmpty())

        store.setShowIncomingPath(false)
        advanceUntilIdle()

        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertEquals(false, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_PATH))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_INCOMING_PATH to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun turningOnTheIncomingPathDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.showIncomingPath)

        store.setShowIncomingPath(true)
        assertTrue(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertFalse(store.state.value.showIncomingSendTime)
        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.linkPreviewsEnabled)
        advanceUntilIdle()

        assertEquals(true, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_PATH))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_INCOMING_PATH to true),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepingAutomaticGifPlaybackOnDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.showIncomingPath)
        assertTrue(prefs.snapshot().isEmpty())

        store.setAutoPlayGIFs(true)
        advanceUntilIdle()

        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.showIncomingPath)
        assertEquals(true, prefs.getBoolean(PersistenceKeys.AUTO_PLAY_GIFS))
        assertEquals(
            mapOf(PersistenceKeys.AUTO_PLAY_GIFS to true),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun turningOffAutomaticGifPlaybackDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertTrue(store.state.value.autoPlayGIFs)

        store.setAutoPlayGIFs(false)
        assertFalse(store.state.value.autoPlayGIFs)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertFalse(store.state.value.showIncomingSendTime)
        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        advanceUntilIdle()

        assertEquals(false, prefs.getBoolean(PersistenceKeys.AUTO_PLAY_GIFS))
        assertEquals(
            mapOf(PersistenceKeys.AUTO_PLAY_GIFS to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepingMapPreviewThumbnailsOnDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertTrue(store.state.value.autoPlayGIFs)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.showIncomingPath)
        assertTrue(prefs.snapshot().isEmpty())

        store.setShowMapPreviewThumbnails(true)
        advanceUntilIdle()

        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertTrue(store.state.value.autoPlayGIFs)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.showIncomingPath)
        assertEquals(true, prefs.getBoolean(PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.AUTO_PLAY_GIFS))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.LINK_PREVIEWS_ENABLED))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.REPLY_WITH_QUOTE))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_PATH))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS to true),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun turningOffMapPreviewThumbnailsDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertTrue(store.state.value.autoPlayGIFs)

        store.setShowMapPreviewThumbnails(false)
        assertFalse(store.state.value.showMapPreviewThumbnails)
        assertTrue(store.state.value.autoPlayGIFs)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.linkPreviewsEnabled)
        advanceUntilIdle()

        assertEquals(false, prefs.getBoolean(PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepingReplyWithQuoteOffDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertTrue(prefs.snapshot().isEmpty())

        store.setReplyWithQuote(false)
        advanceUntilIdle()

        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertEquals(false, prefs.getBoolean(PersistenceKeys.REPLY_WITH_QUOTE))
        assertEquals(
            mapOf(PersistenceKeys.REPLY_WITH_QUOTE to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun turningOnReplyWithQuoteDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.replyWithQuote)

        store.setReplyWithQuote(true)
        assertTrue(store.state.value.replyWithQuote)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertFalse(store.state.value.showIncomingSendTime)
        advanceUntilIdle()

        assertEquals(true, prefs.getBoolean(PersistenceKeys.REPLY_WITH_QUOTE))
        assertEquals(
            mapOf(PersistenceKeys.REPLY_WITH_QUOTE to true),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepingLinkPreviewsOffDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertTrue(store.state.value.linkPreviewsAutoResolveDM)
        assertTrue(store.state.value.linkPreviewsAutoResolveChannels)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.showIncomingPath)
        assertTrue(prefs.snapshot().isEmpty())

        store.setLinkPreviewsEnabled(false)
        advanceUntilIdle()

        assertFalse(store.state.value.linkPreviewsEnabled)
        assertTrue(store.state.value.linkPreviewsAutoResolveDM)
        assertTrue(store.state.value.linkPreviewsAutoResolveChannels)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.showIncomingPath)
        assertEquals(false, prefs.getBoolean(PersistenceKeys.LINK_PREVIEWS_ENABLED))
        assertEquals(null, prefs.getBoolean(LinkPreviewScope.AUTO_RESOLVE_DM_KEY))
        assertEquals(null, prefs.getBoolean(LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.AUTO_PLAY_GIFS))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.REPLY_WITH_QUOTE))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_INCOMING_PATH))
        assertEquals(
            mapOf(PersistenceKeys.LINK_PREVIEWS_ENABLED to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun turningOffLinkPreviewsDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertFalse(store.state.value.linkPreviewsEnabled)

        store.setLinkPreviewsEnabled(true)
        advanceUntilIdle()
        assertTrue(store.state.value.linkPreviewsEnabled)

        store.setLinkPreviewsEnabled(false)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertFalse(store.state.value.showIncomingSendTime)
        assertFalse(store.state.value.replyWithQuote)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.linkPreviewsAutoResolveDM)
        assertTrue(store.state.value.linkPreviewsAutoResolveChannels)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        advanceUntilIdle()

        assertEquals(false, prefs.getBoolean(PersistenceKeys.LINK_PREVIEWS_ENABLED))
        assertEquals(
            mapOf(PersistenceKeys.LINK_PREVIEWS_ENABLED to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepingChannelAutoResolveOnDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertTrue(store.state.value.linkPreviewsAutoResolveChannels)
        assertTrue(store.state.value.linkPreviewsAutoResolveDM)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertTrue(prefs.snapshot().isEmpty())

        store.setLinkPreviewsAutoResolveChannels(true)
        advanceUntilIdle()

        assertTrue(store.state.value.linkPreviewsAutoResolveChannels)
        assertTrue(store.state.value.linkPreviewsAutoResolveDM)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertEquals(true, prefs.getBoolean(LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY))
        assertEquals(null, prefs.getBoolean(LinkPreviewScope.AUTO_RESOLVE_DM_KEY))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.LINK_PREVIEWS_ENABLED))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.AUTO_PLAY_GIFS))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS))
        assertEquals(
            mapOf(LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY to true),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun turningOffChannelLinkAutoResolveDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertTrue(store.state.value.linkPreviewsAutoResolveChannels)
        assertTrue(store.state.value.linkPreviewsAutoResolveDM)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)

        store.setLinkPreviewsAutoResolveChannels(false)
        assertFalse(store.state.value.linkPreviewsAutoResolveChannels)
        assertTrue(store.state.value.linkPreviewsAutoResolveDM)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.replyWithQuote)
        advanceUntilIdle()

        assertEquals(false, prefs.getBoolean(LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY))
        assertEquals(
            mapOf(LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepingDirectAutoResolveOnDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertTrue(store.state.value.linkPreviewsAutoResolveDM)
        assertTrue(store.state.value.linkPreviewsAutoResolveChannels)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertTrue(prefs.snapshot().isEmpty())

        store.setLinkPreviewsAutoResolveDM(true)
        advanceUntilIdle()

        assertTrue(store.state.value.linkPreviewsAutoResolveDM)
        assertTrue(store.state.value.linkPreviewsAutoResolveChannels)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertEquals(true, prefs.getBoolean(LinkPreviewScope.AUTO_RESOLVE_DM_KEY))
        assertEquals(null, prefs.getBoolean(LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.LINK_PREVIEWS_ENABLED))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.AUTO_PLAY_GIFS))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS))
        assertEquals(
            mapOf(LinkPreviewScope.AUTO_RESOLVE_DM_KEY to true),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun turningOffDirectMessageLinkAutoResolveDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)
        assertTrue(store.state.value.linkPreviewsAutoResolveDM)
        assertTrue(store.state.value.linkPreviewsAutoResolveChannels)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)

        store.setLinkPreviewsAutoResolveDM(false)
        assertFalse(store.state.value.linkPreviewsAutoResolveDM)
        assertTrue(store.state.value.linkPreviewsAutoResolveChannels)
        assertFalse(store.state.value.linkPreviewsEnabled)
        assertTrue(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showMapPreviewThumbnails)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.replyWithQuote)
        advanceUntilIdle()

        assertEquals(false, prefs.getBoolean(LinkPreviewScope.AUTO_RESOLVE_DM_KEY))
        assertEquals(
            mapOf(LinkPreviewScope.AUTO_RESOLVE_DM_KEY to false),
            prefs.snapshot(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadingChatDisplayPreferencesDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = ChatDisplayPreferencesStore(prefs, this)

        store.load()
        advanceUntilIdle()

        assertFalse(store.state.value.showIncomingHopCount)
        assertFalse(store.state.value.replyWithQuote)
        assertFalse(store.state.value.showIncomingRegion)
        assertFalse(store.state.value.showIncomingPath)
        assertFalse(store.state.value.showIncomingHeardCount)
        assertFalse(store.state.value.showIncomingSendTime)
        assertTrue(store.state.value.autoPlayGIFs)
        assertEquals(emptyMap(), prefs.snapshot())
    }
}
