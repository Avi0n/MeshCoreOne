package one.meshcore.shared.chat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Loads and writes the aspect map. A missing or corrupt snapshot starts empty, matching iOS.
 * Not part of the backup envelope.
 */
interface InlineImageDimensionsPersister {
    fun load(): Map<String, Double>
    fun save(aspects: Map<String, Double>)
}

/**
 * Cache of inline-image aspect ratios (width / height). Wait-free [aspect] reads feed message item
 * builds so bubbles reserve the right height without a layout jump. Mirrors the Swift
 * `InlineImageDimensionsStore`, including the disposable on-disk JSON snapshot.
 */
class InlineImageDimensionsStore(private val persister: InlineImageDimensionsPersister? = null) {
    private val aspects = linkedMapOf<String, Double>()
    private val _resolutions = MutableSharedFlow<String>(extraBufferCapacity = RESOLUTION_BUFFER)
    val resolutions: SharedFlow<String> = _resolutions.asSharedFlow()

    init {
        persister?.load()?.forEach { (url, aspect) ->
            if (aspect > 0.0) aspects[url] = aspect
        }
    }

    /** Cached width/height ratio for [url], or null when never saved. */
    fun aspect(url: String): Double? = aspects[url]

    /**
     * Records a successful dimension probe. Rejects non-positive sizes. Emits [url] on [resolutions]
     * so open threads can rebuild rows with the reserved aspect.
     */
    fun save(url: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val aspect = width.toDouble() / height.toDouble()
        if (aspects.size >= CACHE_LIMIT && !aspects.containsKey(url)) {
            val oldest = aspects.keys.firstOrNull()
            if (oldest != null) aspects.remove(oldest)
        }
        aspects[url] = aspect
        _resolutions.tryEmit(url)
        persister?.save(aspects.toMap())
    }

    fun clear() {
        aspects.clear()
        persister?.save(emptyMap())
    }

    private companion object {
        const val CACHE_LIMIT = 500
        const val RESOLUTION_BUFFER = 64
    }
}
