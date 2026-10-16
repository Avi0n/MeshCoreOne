package one.meshcore.shared.settings

import kotlinx.serialization.json.Json

/**
 * The JSON codec for [MeshCoreNodeConfig], frozen to the companion-app format: `explicitNulls = false` so an
 * absent optional section/field is omitted (matching Swift's `encodeIfPresent` default), while
 * [ContactConfigSerializer] still forces `custom_name`/`out_path` to explicit `null`. `prettyPrint` matches
 * the Swift exporter's `.prettyPrinted, .sortedKeys` for diff-friendly files; `ignoreUnknownKeys` tolerates
 * forward-compatible additions on decode.
 */
object NodeConfigJson {
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** Encodes a config to its companion-app JSON string. */
    fun encode(config: MeshCoreNodeConfig): String = json.encodeToString(MeshCoreNodeConfig.serializer(), config)

    /** Decodes a config from a companion-app JSON string. */
    fun decode(text: String): MeshCoreNodeConfig = json.decodeFromString(MeshCoreNodeConfig.serializer(), text)
}
