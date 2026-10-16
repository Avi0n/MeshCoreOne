package one.meshcore.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Serializable snapshot of the running app; the first cross-platform golden value. */
@Serializable
data class AppBuildInfo(val app: String, val platform: String)

/** Small, platform-agnostic facts about the app, shared by every target. */
object AppInfo {
    const val APP_NAME: String = "MeshCore One"

    private val json = Json { prettyPrint = false }

    /** One-line description of where the shared code is running. */
    fun summarize(platform: String): String = "$APP_NAME on $platform"

    fun encode(info: AppBuildInfo): String = json.encodeToString(AppBuildInfo.serializer(), info)

    fun decode(payload: String): AppBuildInfo = json.decodeFromString(AppBuildInfo.serializer(), payload)
}
