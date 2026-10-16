package one.meshcore.shared.chat

/**
 * Which fragments render inside the colored bubble box versus as siblings below it.
 *
 * Box-vs-sibling rule: [MessageFragment.Text] and [MessageFragment.InlineImage] render inside the
 * box; every other kind renders beneath it, preserving document order. First text and first inline
 * image win; later box kinds are dropped. Mirrors iOS `FragmentLayout`.
 */
data class FragmentLayout(
    val textPayload: MessageTextPayload?,
    val inlineImage: InlineImage?,
    val siblings: List<MessageFragment>,
)

fun fragmentLayout(content: List<MessageFragment>): FragmentLayout {
    var textPayload: MessageTextPayload? = null
    var inlineImage: InlineImage? = null
    val siblings = ArrayList<MessageFragment>()
    for (fragment in content) {
        when (fragment) {
            is MessageFragment.Text -> if (textPayload == null) textPayload = fragment.payload
            is MessageFragment.InlineImage -> if (inlineImage == null) inlineImage = fragment.image
            is MessageFragment.LinkPreview,
            is MessageFragment.MapPreview,
            is MessageFragment.MalwareWarning,
            is MessageFragment.ReactionSummary,
            -> siblings += fragment
        }
    }
    return FragmentLayout(textPayload, inlineImage, siblings)
}
