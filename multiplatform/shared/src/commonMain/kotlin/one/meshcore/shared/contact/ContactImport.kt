package one.meshcore.shared.contact

import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.model.ContactType

/** The public key as a hex string is twice its byte length: 64 characters for the 32-byte key. */
const val PUBLIC_KEY_HEX_LENGTH: Int = ChatConstants.PUBLIC_KEY_SIZE_BYTES * 2

/**
 * The longest advertised name the firmware stores, in UTF-8 bytes (iOS `ProtocolLimits.maxUsableNameBytes`).
 * The Add-contact name field truncates on this byte boundary as the user types, not on a character count,
 * so a multi-byte name is cut where the buffer would cut it.
 */
const val MAX_USABLE_NAME_BYTES: Int = 31

private const val UTF8_CONTINUATION_MASK: Int = 0xC0
private const val UTF8_CONTINUATION_MARKER: Int = 0x80

/**
 * The single contact-frame construction shared by the Add and Scan import paths: a contact reachable
 * only by flooding (`outPathLength` = the flood sentinel, empty path), no flags, never advertised
 * (`lastAdvertTimestamp` 0), no location, stamped with [nowSeconds]. Mirrors the identical
 * `ContactFrame(...)` the iOS `AddContactSheet.handleAdd` and `ScanContactQRView.importContact` build.
 * The caller supplies [nowSeconds] and the resolved `radioID`, keeping the three-axis identity invariant
 * (no minted radioID here).
 */
internal fun importContactFrame(
    name: String,
    publicKey: ByteArray,
    type: ContactType,
    nowSeconds: Long,
): ContactFrame = ContactFrame(
    publicKey = publicKey,
    type = type,
    flags = 0,
    outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
    outPath = ByteArray(0),
    name = name,
    lastAdvertTimestamp = 0,
    latitude = 0.0,
    longitude = 0.0,
    lastModified = nowSeconds,
)

/**
 * The longest prefix of this string whose UTF-8 encoding is at most [maxBytes] bytes, cut on a UTF-8
 * character boundary so a multi-byte character is never split. Mirrors the iOS `String.utf8Prefix(maxBytes:)`
 * the name field applies live.
 */
internal fun String.utf8BytePrefix(maxBytes: Int): String {
    val bytes = encodeToByteArray()
    if (bytes.size <= maxBytes) return this
    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_MARKER) {
        end--
    }
    return bytes.copyOfRange(0, end).decodeToString()
}
