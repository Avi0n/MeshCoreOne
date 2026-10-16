package one.meshcore.shared.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON snapshot of inline-image aspects, the Android/desktop counterpart of the Swift
 * `InlineImageDimensions.json` file. A missing or corrupt file starts empty.
 */
class FileInlineImageDimensionsPersister(private val file: File) : InlineImageDimensionsPersister {
    override fun load(): Map<String, Double> {
        if (!file.exists()) return emptyMap()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
        val decoded = runCatching { json.decodeFromString(EntryMapSerializer, text) }.getOrNull()
            ?: return emptyMap()
        return decoded.mapValues { it.value.aspect }.filterValues { it > 0.0 }
    }

    override fun save(aspects: Map<String, Double>) {
        val now = System.currentTimeMillis() / MILLIS_PER_SECOND
        val entries = aspects.mapValues { Entry(aspect = it.value, fetchedAt = now) }
        val text = runCatching { json.encodeToString(EntryMapSerializer, entries) }.getOrNull() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    @Serializable
    private data class Entry(val aspect: Double, val fetchedAt: Double)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val EntryMapSerializer = kotlinx.serialization.serializer<Map<String, Entry>>()
        const val MILLIS_PER_SECOND = 1000.0
    }
}
