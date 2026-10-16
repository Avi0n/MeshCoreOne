package one.meshcore.shared.navigation

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.crypto.sha256
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import one.meshcore.shared.model.toUppercaseHex

/**
 * Discriminates the two send targets a Shortcuts / App Action picker can represent.
 * Raw values are pinned because they are the kind segment of a persisted composite id.
 */
enum class MessageTargetKind(val raw: String) {
    CONTACT("contact"),
    CHANNEL("channel"),
    ;

    companion object {
        fun fromRaw(raw: String): MessageTargetKind? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * A radio-scoped send target. [id] is `"<radioID>/<kind>/<keyOrDigestHex>"`: contacts key on the
 * public key, channels on a non-reversible secret digest. Never the row UUID or firmware slot.
 */
data class MessageTarget(val id: String, val kind: MessageTargetKind, val displayName: String, val subtitle: String)

fun formatCompositeId(radioId: Uuid, kind: MessageTargetKind, keyHex: String): String =
    "${radioId.value}$COMPOSITE_ID_SEPARATOR${kind.raw}$COMPOSITE_ID_SEPARATOR$keyHex"

fun parseCompositeId(id: String): Triple<Uuid, MessageTargetKind, String>? {
    val first = id.indexOf(COMPOSITE_ID_SEPARATOR).takeIf { it > 0 } ?: return null
    val second = id.indexOf(COMPOSITE_ID_SEPARATOR, startIndex = first + 1).takeIf { it > first + 1 } ?: return null
    val kind = MessageTargetKind.fromRaw(id.substring(first + 1, second)) ?: return null
    val keyHex = id.substring(second + 1).takeIf { it.isNotEmpty() } ?: return null
    val radioPart = id.substring(0, first)
    if (!COMPOSITE_RADIO_ID.matches(radioPart)) return null
    return Triple(Uuid.normalized(radioPart), kind, keyHex)
}

/**
 * Non-reversible digest of a channel secret, domain-separated and radio-scoped.
 * The raw secret never leaves the device inside a persisted shortcut id.
 */
fun channelSecretDigestHex(radioId: Uuid, secret: ByteArray): String {
    val radioBytes = radioId.rfc4122Bytes()
    val domain = CHANNEL_DIGEST_DOMAIN.encodeToByteArray()
    val input = ByteArray(domain.size + radioBytes.size + secret.size)
    domain.copyInto(input)
    radioBytes.copyInto(input, destinationOffset = domain.size)
    secret.copyInto(input, destinationOffset = domain.size + radioBytes.size)
    return sha256(input).copyOf(CHANNEL_DIGEST_BYTE_COUNT).toLowercaseHex()
}

fun MessageTarget(dto: ContactDto): MessageTarget = MessageTarget(
    id = formatCompositeId(dto.radioID, MessageTargetKind.CONTACT, dto.publicKey.toLowercaseHex()),
    kind = MessageTargetKind.CONTACT,
    displayName = dto.displayName,
    subtitle = dto.publicKey.copyOf(CONTACT_PUBLIC_KEY_PREFIX_BYTES).toLowercaseHex(),
)

fun MessageTarget(dto: ChannelDto): MessageTarget = MessageTarget(
    id = formatCompositeId(
        dto.radioID,
        MessageTargetKind.CHANNEL,
        channelSecretDigestHex(dto.radioID, dto.secret),
    ),
    kind = MessageTargetKind.CHANNEL,
    displayName = dto.name,
    subtitle = "",
)

/** Chat contacts only: a DM target must be a person, never a repeater or room. */
fun chatContacts(contacts: List<ContactDto>): List<ContactDto> =
    contacts.filter { it.typeRawValue == ContactType.CHAT.raw }

data class SendMessageShortcutSpec(
    val shortcutId: String,
    val displayName: String,
    val kind: MessageTargetKind,
    val rowId: String,
    val url: String,
)

/** Dynamic-shortcut budget left after the three static launcher pins. */
const val MAX_SEND_MESSAGE_SHORTCUTS: Int = 12

const val SEND_MESSAGE_SHORTCUT_ID_PREFIX: String = "send-target-"

/**
 * Assistant inventory: one spec per uniquely resolvable chat contact or channel, capped so the
 * host stays inside the platform shortcut limit. The URL carries the row id; [name] is the spoken
 * label for the confirm dialog.
 */
fun sendMessageShortcutSpecs(
    contacts: List<ContactDto>,
    channels: List<ChannelDto>,
    maxCount: Int = MAX_SEND_MESSAGE_SHORTCUTS,
): List<SendMessageShortcutSpec> {
    val chat = chatContacts(contacts)
    return buildMessageTargets(chat, channels).take(maxCount).mapNotNull { target ->
        val rowId = when (target.kind) {
            MessageTargetKind.CONTACT -> chat.firstOrNull { MessageTarget(it).id == target.id }?.id?.value
            MessageTargetKind.CHANNEL -> channels.firstOrNull { MessageTarget(it).id == target.id }?.id?.value
        } ?: return@mapNotNull null
        SendMessageShortcutSpec(
            shortcutId = SEND_MESSAGE_SHORTCUT_ID_PREFIX + rowId,
            displayName = target.displayName,
            kind = target.kind,
            rowId = rowId,
            url = "meshcore://send?kind=${target.kind.raw}&id=$rowId&name=${target.displayName.encodeSendQuery()}",
        )
    }
}

private fun String.encodeSendQuery(): String = buildString {
    for (byte in this@encodeSendQuery.encodeToByteArray()) {
        val unsigned = byte.toInt() and BYTE_MASK
        val ascii = unsigned.toChar()
        when {
            ascii.isLetterOrDigit() || ascii == '-' || ascii == '_' || ascii == '.' || ascii == '~' -> append(ascii)
            unsigned == SPACE_BYTE -> append("%20")
            else -> append('%').append(byteArrayOf(byte).toUppercaseHex())
        }
    }
}

internal fun Uuid.rfc4122Bytes(): ByteArray {
    val hex = value.replace("-", "")
    require(hex.length == UUID_HEX_LENGTH) { "Uuid is not 16 bytes: $value" }
    return ByteArray(UUID_BYTE_COUNT) { index ->
        hex.substring(index * HEX_CHARS_PER_BYTE, index * HEX_CHARS_PER_BYTE + HEX_CHARS_PER_BYTE)
            .toInt(HEX_RADIX)
            .toByte()
    }
}

internal val COMPOSITE_RADIO_ID = Regex(
    "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$",
)
private const val COMPOSITE_ID_SEPARATOR = '/'
private const val CHANNEL_DIGEST_BYTE_COUNT = 16
private const val CHANNEL_DIGEST_DOMAIN = "MC1.ChannelEntity.id.v1"
private const val CONTACT_PUBLIC_KEY_PREFIX_BYTES = 6
private const val UUID_HEX_LENGTH = 32
private const val UUID_BYTE_COUNT = 16
private const val HEX_CHARS_PER_BYTE = 2
private const val HEX_RADIX = 16
private const val BYTE_MASK = 0xFF
private const val SPACE_BYTE = 0x20
