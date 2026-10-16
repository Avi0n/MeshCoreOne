package one.meshcore.shared.navigation

/**
 * A bare `meshcore://<host>` URL that names a place in the app rather than a piece of content — what the
 * launcher shortcuts emit. Distinguished from the content links by carrying no query and no path: a
 * `meshcore://map?lat=&lon=` is a map focus and is parsed by [MeshCoreUrlParser] before this ever runs.
 *
 * Android-new. iOS exposes the same three glances as App Intents (`MC1AppShortcutsProvider`), a headless
 * Siri/Spotlight surface with no launcher-shortcut form; a static Android shortcut can only start the
 * Activity, so the honest equivalent is to open the app where that information lives.
 */
enum class AppShortcutDestination(private val host: String) {
    /** The Chats tab. */
    CHATS("chats"),

    /** The Map tab, with no pin (a coordinate link goes through the map-focus parse instead). */
    MAP("map"),

    /** Settings → My Device, the surface carrying radio name, connection state, and battery. */
    RADIO_STATUS("status"),
    ;

    companion object {
        /** The destination [url] names, or null when it is not a bare `meshcore://<host>` URL. */
        fun parse(url: String): AppShortcutDestination? {
            if (url.substringBefore("://", missingDelimiterValue = "") != MeshCoreUrlParser.SCHEME) return null
            val rest = url.substringAfter("://")
            if (rest.contains('?')) return null
            val host = rest.substringBefore('/')
            return entries.firstOrNull { it.host == host }
        }
    }
}
