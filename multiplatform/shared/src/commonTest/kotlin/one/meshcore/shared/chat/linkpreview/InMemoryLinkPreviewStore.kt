package one.meshcore.shared.chat.linkpreview

/** In-memory link-preview store that records reads and writes for tests. */
class InMemoryLinkPreviewStore : LinkPreviewStore {
    private val rows = mutableMapOf<String, LinkPreviewData>()
    val saveCount get() = saves
    private var saves = 0
    var fetchCount = 0
        private set

    override suspend fun fetchLinkPreview(url: String): LinkPreviewData? {
        fetchCount++
        return rows[url]
    }

    override suspend fun saveLinkPreview(data: LinkPreviewData) {
        saves++
        rows[data.url] = data
    }

    /** Seeds a row without counting it as a save. */
    fun seed(data: LinkPreviewData) {
        rows[data.url] = data
    }
}
