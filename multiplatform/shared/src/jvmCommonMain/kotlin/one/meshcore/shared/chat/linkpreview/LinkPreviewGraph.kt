package one.meshcore.shared.chat.linkpreview

import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomLinkPreviewStore
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.urlsafety.InetAddressHostResolver
import one.meshcore.shared.urlsafety.MalwareDomainFilter
import one.meshcore.shared.urlsafety.createMalwareDomainFilter
import java.io.File

/**
 * Process-lifetime link-preview graph for Android/desktop. Keeps Ktor and Room construction inside
 * `:shared` so app modules never depend on those classpaths for wiring.
 */
fun createLinkPreviewCache(
    database: MeshDatabase,
    settings: LinkPreviewSettings,
    scope: CoroutineScope,
    now: () -> Double = { 0.0 },
): LinkPreviewCache = LinkPreviewCache(
    fetcher = LinkPreviewService(
        resolver = InetAddressHostResolver,
        fetcher = KtorLinkContentFetcher(
            client = linkPreviewHttpClient(),
            resolver = InetAddressHostResolver,
        ),
    ),
    store = RoomLinkPreviewStore(database.linkPreviewDao()),
    preferences = LinkPreviewPreferences(settings),
    scope = scope,
    now = now,
)

/**
 * Production malware filter: disk cache → bundled URLhaus snapshot → background network refresh.
 * [cacheDirectory] holds [MalwareDomainFilter.CACHE_FILENAME]; [preferences] stores the last-refresh stamp.
 */
fun defaultMalwareDomainFilter(
    cacheDirectory: File,
    preferences: PreferencesStore,
    scope: CoroutineScope,
): MalwareDomainFilter = createMalwareDomainFilter(
    cacheDirectory = cacheDirectory,
    preferences = preferences,
    scope = scope,
)
