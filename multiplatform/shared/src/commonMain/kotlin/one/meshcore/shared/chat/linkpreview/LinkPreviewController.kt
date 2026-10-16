package one.meshcore.shared.chat.linkpreview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import one.meshcore.shared.chat.CachedDecodedImage
import one.meshcore.shared.chat.PreviewLoadState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.urlsafety.MalwareDomainFilter

/**
 * Drives per-message link-preview fetching for one open conversation, the headless port of the preview
 * surface of the iOS `ChatViewModel+Prefetch` extension. It owns the per-message [PreviewLoadState], the
 * detected URL, and the resolved preview, and it places the SAFETY-CRITICAL malware gate exactly where iOS
 * does — once, up front, in this view-model layer, before any cache or network work (`URLSafetyChecker`
 * itself never consults the blocklist). Inline-image bytes are fetched through [LinkPreviewCache]'s
 * gated cache (the Kotlin `InlineImageCache`) after those same checks, matching iOS `fetchInlineImage`.
 * A resolved state feeds the fragment builder through [previewState]/[cachedUrl]/[loadedPreview]; each
 * transition calls [onRebuild] so the owning view model rebuilds the row.
 *
 * Single-confinement like the iOS `@MainActor` original: every method runs on the caller's confinement
 * dispatcher (the injected [scope] shares it), so the plain maps need no lock. [rehydrate] deliberately
 * repaints a cached preview to loaded WITHOUT re-consulting the malware filter — a flagged URL never entered
 * the cache (its fetch returned early), so the skip is safe and matches iOS.
 */
class LinkPreviewController(
    internal val cache: LinkPreviewCache,
    internal val malwareFilter: MalwareDomainFilter,
    internal val scope: CoroutineScope,
    internal val isChannelMessage: () -> Boolean,
    internal val onRebuild: (Uuid) -> Unit,
) {
    internal val previewStates = mutableMapOf<Uuid, PreviewLoadState>()
    internal val detectedUrls = mutableMapOf<Uuid, String?>()
    internal val loadedPreviews = mutableMapOf<Uuid, LinkPreviewData>()
    internal val loadedInline = mutableMapOf<Uuid, CachedDecodedImage>()
    internal val fetchJobs = mutableMapOf<Uuid, Job>()

    /**
     * Image-extension URLs the fetch path has discovered serve an HTML page.
     * Keyed by the message URL so one discovery reroutes every row sharing it.
     * Cleared on conversation switch; [LinkPreviewCache.servesHtmlPageCached] carries
     * the verdict across re-entry.
     */
    internal val imageUrlsServingPages = mutableSetOf<String>()
}
