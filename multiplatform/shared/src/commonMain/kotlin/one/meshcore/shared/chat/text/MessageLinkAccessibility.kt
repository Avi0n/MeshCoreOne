package one.meshcore.shared.chat.text

import one.meshcore.shared.navigation.MeshCoreUrlParser

/**
 * The VoiceOver/TalkBack "open" actions for a message bubble's links. The body text is a passive renderer
 * (no per-run link interaction), so these actions restore activation for every link kind. Ported from the
 * iOS `MessageLinkAccessibility`; the action *kind* is resolved here in the framework-free layer and the
 * UI maps it to a localized `L10n` string, so the label and the tap dispatch can never disagree about a
 * link's target.
 */
object MessageLinkAccessibility {
    /**
     * Upper bound on the actions one message contributes, so a link-packed message cannot flood the rotor.
     * Links past the cap stay visible but are not separately activatable.
     */
    const val MAX_ACTIONS: Int = 8

    /** A localized-later action label kind plus its interpolation argument. */
    sealed interface Label {
        data object OpenLink : Label
        data class OpenWebLink(val host: String) : Label
        data object OpenMapLink : Label
        data class AddContact(val name: String) : Label
        data class OpenChannel(val name: String) : Label
        data class OpenMention(val name: String) : Label
        data class OpenHashtag(val channel: String) : Label
    }

    /** One openable link: the label kind the UI localizes, plus the URL the action opens. */
    data class Action(val label: Label, val url: String)

    /**
     * Openable links in document order: the preview card's URL first (if any), then each linked span in
     * the body. Deduplicated by URL and capped at [MAX_ACTIONS].
     */
    fun actions(previewUrl: String?, spans: List<TextSpan>): List<Action> {
        val seen = mutableSetOf<String>()
        val actions = mutableListOf<Action>()

        fun append(url: String) {
            if (actions.size >= MAX_ACTIONS || !seen.add(url)) return
            actions += Action(label = labelFor(url), url = url)
        }

        previewUrl?.let(::append)
        spans.forEach { span -> span.url?.let(::append) }
        return actions
    }

    /** The target-bearing label kind for [url], so a screen-reader user can tell two links apart. */
    private fun labelFor(url: String): Label {
        val parts = parseMeshLink(url) ?: return Label.OpenLink
        return when (parts.scheme.lowercase()) {
            "http", "https" -> webHost(url)?.let { Label.OpenWebLink(it) } ?: Label.OpenLink
            MeshCoreUrlParser.SCHEME -> meshCoreLabel(url, parts.host)
            MentionDeeplink.SCHEME -> meshCoreOneLabel(url, parts.host)
            else -> Label.OpenLink
        }
    }

    private fun meshCoreLabel(url: String, host: String): Label = when (host) {
        "map" -> Label.OpenMapLink
        "contact" -> MeshCoreUrlParser.parseContactUrl(url)?.let { Label.AddContact(it.name) } ?: Label.OpenLink
        "channel" -> MeshCoreUrlParser.parseChannelUrl(url)?.let { Label.OpenChannel(it.name) } ?: Label.OpenLink
        else -> Label.OpenLink
    }

    private fun meshCoreOneLabel(url: String, host: String): Label = when (host) {
        MentionDeeplink.HOST -> MentionDeeplink.name(url)?.let { Label.OpenMention(it) } ?: Label.OpenLink
        HashtagDeeplink.HOST -> HashtagDeeplink.channelNameFromUrl(url)?.let { Label.OpenHashtag(it) } ?: Label.OpenLink
        else -> Label.OpenLink
    }

    /** Host of an `http`/`https` URL (authority minus any userinfo and port), or null when absent. */
    private fun webHost(url: String): String? {
        val parts = parseMeshLink(url) ?: return null
        val authority = parts.host.substringAfterLast('@').substringBefore(':')
        return authority.ifEmpty { null }
    }
}
