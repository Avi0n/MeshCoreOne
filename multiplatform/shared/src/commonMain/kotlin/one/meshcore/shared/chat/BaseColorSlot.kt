package one.meshcore.shared.chat

/**
 * Direction-tagged colour slot for chat-message text. The UI layer resolves `OUTGOING` to the filled
 * bubble's text colour and `INCOMING` to the primary colour at render time, so the content model stays
 * free of any concrete UI colour type. Mirrors the Swift `BaseColorSlot`.
 */
enum class BaseColorSlot {
    OUTGOING,
    INCOMING,
}
