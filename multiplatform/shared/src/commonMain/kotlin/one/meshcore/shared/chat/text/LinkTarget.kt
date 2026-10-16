package one.meshcore.shared.chat.text

import one.meshcore.shared.navigation.MeshCoreUrlParser

/**
 * The semantic destination a linkified span's URL resolves to, so the Compose tap handler dispatches on a
 * typed value instead of re-parsing a raw string at the call site. [parse] is the single classifier both
 * the renderer's tap seam and any router wiring read from.
 */
sealed interface LinkTarget {
    /** An external `http`/`https` link — opened in the browser. */
    data class Web(val url: String) : LinkTarget

    /** A `meshcore://contact` or `meshcore://channel` deep link — routed through the deep-link router. */
    data class MeshCoreLink(val url: String) : LinkTarget

    /** A `meshcoreone://mention/<name>` tap — resolved to a contact or a disambiguation picker. */
    data class Mention(val name: String) : LinkTarget

    /** A `meshcoreone://hashtag/<name>` tap — resolved to an existing channel or the join seam. */
    data class Hashtag(val name: String) : LinkTarget

    /** A `meshcore://map?lat=&lon=` coordinate tap — centres the map on the pin. */
    data class Coordinate(val latitude: Double, val longitude: Double) : LinkTarget

    companion object {
        /** Classifies [url], or null when no chat surface claims it. */
        fun parse(url: String): LinkTarget? {
            val parts = parseMeshLink(url) ?: return null
            return when (parts.scheme.lowercase()) {
                "http", "https" -> Web(url)
                MeshCoreUrlParser.SCHEME -> meshCoreTarget(url, parts.host)
                MentionDeeplink.SCHEME -> meshCoreOneTarget(url, parts.host)
                else -> null
            }
        }

        private fun meshCoreTarget(url: String, host: String): LinkTarget? = when (host) {
            "map" -> MeshCoreUrlParser.parseMapUrl(url)?.let { Coordinate(it.latitude, it.longitude) }
            "contact", "channel" -> MeshCoreLink(url)
            else -> null
        }

        private fun meshCoreOneTarget(url: String, host: String): LinkTarget? = when (host) {
            MentionDeeplink.HOST -> MentionDeeplink.name(url)?.let { Mention(it) }
            HashtagDeeplink.HOST -> HashtagDeeplink.channelNameFromUrl(url)?.let { Hashtag(it) }
            else -> null
        }
    }
}
