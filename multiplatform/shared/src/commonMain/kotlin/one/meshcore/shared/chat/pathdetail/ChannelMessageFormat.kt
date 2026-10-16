package one.meshcore.shared.chat.pathdetail

/** The sender name and trimmed body split out of a decrypted `"NodeName: MessageText"` channel message. */
data class ChannelMessageParts(val senderName: String, val messageText: String)

/**
 * Parses the `"NodeName: MessageText"` wire format the firmware prepends to a channel message before
 * encryption. Ported from the Swift `ChannelMessageFormat.parse`, which the heard-repeats matcher uses.
 *
 * This is deliberately distinct from the ingest pipeline's `parseChannelMessage`: here a message whose
 * colon is the last character parses as an empty body (sender + ""), whereas the ingest parser treats an
 * empty body as unparseable. Both split on the first colon and preserve the parity the Swift sources chose.
 */
object ChannelMessageFormat {
    /**
     * Splits [text] on its first colon into a sender and a whitespace-trimmed body, or null when there is
     * no colon or the colon is the first character (an empty sender). A colon with nothing after it yields
     * an empty body. The sender name is returned verbatim (spaces preserved, not trimmed).
     */
    fun parse(text: String): ChannelMessageParts? {
        val colonIndex = text.indexOf(':')
        if (colonIndex <= 0) return null
        val senderName = text.substring(0, colonIndex)
        val body = text.substring(colonIndex + 1).trim()
        return ChannelMessageParts(senderName = senderName, messageText = body)
    }
}
