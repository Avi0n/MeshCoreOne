package one.meshcore.shared.contact

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.decodeHex
import one.meshcore.shared.model.toUppercaseHex

/**
 * The chat-text contact share token: `<publicKeyHex:type:name>`. Produced when a contact card is
 * shared into a chat and recovered by chat linkify. Byte-identical to the iOS `ContactShareUtilities`.
 *
 * - `publicKeyHex` — the 32-byte key as 64 hex chars, uppercase on emit, case-insensitive on parse.
 * - `type` — a [ContactType] raw value in decimal.
 * - `name` — the advertised name. `>` is the reserved terminator and is stripped from the name on emit;
 *   the name may legitimately contain `:`, newlines, and RTL overrides.
 *
 * Unlike [ContactCardUri], the token parser has no default type: a type outside the `UInt8` range or
 * outside [ContactType] is rejected, matching the adversarial iOS tests.
 */
object ContactShareToken {
    /** Matches a token: 64 hex, decimal type, non-empty name up to the reserved `>`. */
    const val PATTERN: String = """<[0-9a-fA-F]{64}:\d+:[^>]+>"""

    private val regex = Regex(PATTERN)

    private const val TOKEN_OPEN = '<'
    private const val TOKEN_CLOSE = '>'
    private const val FIELD_SEPARATOR = ':'

    /** Keeps the public key and type as their own fields while the name retains internal `:`. */
    private const val EXPECTED_FIELD_COUNT = 3

    private const val PUBLIC_KEY_SIZE_BYTES = 32
    private val TYPE_RANGE = 0..UByte.MAX_VALUE.toInt()

    /** Formats a contact into a share token; any `>` in the name is stripped so it can't terminate early. */
    fun format(publicKey: ByteArray, type: ContactType, name: String): String {
        val hex = publicKey.toUppercaseHex()
        val sanitizedName = name.filter { it != TOKEN_CLOSE }
        return "$TOKEN_OPEN$hex$FIELD_SEPARATOR${type.raw}$FIELD_SEPARATOR$sanitizedName$TOKEN_CLOSE"
    }

    /** Parses the first share token found in [text], or `null` if none is valid. */
    fun parse(text: String): ContactCard? = regex.find(text)?.let { contactCard(it.value) }

    /** Extracts every share token from [text] in order (used to detect multiple cards in one message). */
    fun extractAll(text: String): List<ContactCard> = regex.findAll(text).mapNotNull { contactCard(it.value) }.toList()

    private fun contactCard(matched: String): ContactCard? {
        val interior = matched.substring(1, matched.length - 1)
        // limit = 3 keeps any internal `:` in the name, mirroring Swift split(maxSplits: 2).
        val fields = interior.split(FIELD_SEPARATOR, limit = EXPECTED_FIELD_COUNT)
        if (fields.size != EXPECTED_FIELD_COUNT) return null

        val publicKey = decodeHex(fields[0])?.takeIf { it.size == PUBLIC_KEY_SIZE_BYTES }
        val contactType = fields[1].toIntOrNull()?.takeIf { it in TYPE_RANGE }?.let(ContactType::fromRaw)
        val name = fields[2]

        return if (publicKey != null && contactType != null && name.isNotEmpty()) {
            ContactCard(name = name, publicKey = publicKey, contactType = contactType)
        } else {
            null
        }
    }
}
