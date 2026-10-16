package one.meshcore.shared.chat.ui

import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.chat.InlineImageDimensionsStore
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.map.MapSnapshotStore
import one.meshcore.shared.settings.ChatDisplayPreferences
import one.meshcore.shared.urlsafety.MalwareDomainFilter

interface ChatThreadDisplayHost {
    val showIncomingSendTime: () -> Boolean

    /** Auto-play animated GIFs; defaults on to match iOS AppStorage `defaultAutoPlayGIFs`. */
    val autoPlayGIFs: () -> Boolean

    val showIncomingPath: () -> Boolean

    val showIncomingHopCount: () -> Boolean

    val showIncomingRegion: () -> Boolean

    val showIncomingHeardCount: () -> Boolean

    /** Reply-with-quote chat preference; default false makes the default reply a bare @name mention (iOS). */
    val replyWithQuote: () -> Boolean

    val previewsEnabled: () -> Boolean

    /** Chat map thumbnails (privacy-gated); defaults on to match iOS AppStorage. */
    val showMapPreviews: () -> Boolean

    /** Process-lifetime map thumbnail resolution cache (shared across threads). */
    val mapSnapshotStore: () -> MapSnapshotStore?

    /** True when there is no usable network route (map snapshot request key + failure retry). */
    val isNetworkOffline: () -> Boolean

    /**
     * Live chat display toggles. When present the open thread collects this so a Settings change
     * rebakes path/hop/region/preview rows without leaving the conversation.
     */
    val displayPreferences: StateFlow<ChatDisplayPreferences>?

    /**
     * Live network-offline flag. When present the open thread collects this so offline→online
     * clears sticky map-snapshot failures and rebakes thumbnails.
     */
    val networkOffline: StateFlow<Boolean>?

    /** Process-lifetime inline-image aspect cache (shared across threads). */
    val inlineImageDimensions: () -> InlineImageDimensionsStore?

    /** Process-lifetime link-preview cache (shared across threads). */
    val linkPreviewCache: () -> LinkPreviewCache?

    /** Malware host blocklist for link-preview URLs. */
    val malwareFilter: () -> MalwareDomainFilter?
}

/** Chat toggles and the process-lifetime preview caches the open thread collects. */
data class ChatThreadDisplayInputs(
    override val showIncomingSendTime: () -> Boolean = { false },
    override val autoPlayGIFs: () -> Boolean = { true },
    override val showIncomingPath: () -> Boolean = { false },
    override val showIncomingHopCount: () -> Boolean = { false },
    override val showIncomingRegion: () -> Boolean = { false },
    override val showIncomingHeardCount: () -> Boolean = { false },
    override val replyWithQuote: () -> Boolean = { false },
    override val previewsEnabled: () -> Boolean = { false },
    override val showMapPreviews: () -> Boolean = { true },
    override val mapSnapshotStore: () -> MapSnapshotStore? = { null },
    override val isNetworkOffline: () -> Boolean = { false },
    override val displayPreferences: StateFlow<ChatDisplayPreferences>? = null,
    override val networkOffline: StateFlow<Boolean>? = null,
    override val inlineImageDimensions: () -> InlineImageDimensionsStore? = { null },
    override val linkPreviewCache: () -> LinkPreviewCache? = { null },
    override val malwareFilter: () -> MalwareDomainFilter? = { null },
) : ChatThreadDisplayHost
