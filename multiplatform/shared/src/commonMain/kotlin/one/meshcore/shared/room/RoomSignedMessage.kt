package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.settings.PersistentLogger

/** Author public-key prefix length encoded at the start of a signed room message signature. */
const val ROOM_AUTHOR_KEY_PREFIX_BYTES: Int = 4

/** iOS `SyncCoordinator.handleIncomingSignedMessage` drop dump. */
const val DROPPING_SIGNED_MESSAGE_INVALID_PREFIX =
    "Dropping signed message: missing or invalid author prefix"

/** iOS `Failed to handle room message:` persist dump. */
const val FAILED_TO_HANDLE_ROOM_MESSAGE = "Failed to handle room message"

/**
 * Extracts the 4-byte author key prefix from a signed room [ContactMessage], or null when the
 * signature is missing/short. Mirrors iOS `SyncCoordinator.handleIncomingSignedMessage`.
 */
fun authorPrefixFromSignedRoomMessage(message: ContactMessage): ByteArray? {
    val signature = message.signature ?: return null
    if (signature.size < ROOM_AUTHOR_KEY_PREFIX_BYTES) return null
    return signature.copyOfRange(0, ROOM_AUTHOR_KEY_PREFIX_BYTES)
}

/**
 * Ingests a signed room post: drop and log a missing author prefix, persist through [handle],
 * then notify when a new row was saved. A persist fault, including an [Error], is logged and
 * swallowed so the live collector does not treat it as a failed contact save. Cancellation stops
 * the ingest.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun ingestSignedRoomMessage(
    message: ContactMessage,
    logger: PersistentLogger?,
    handle: suspend (authorPrefix: ByteArray) -> RoomMessageDto?,
    onSaved: suspend (RoomMessageDto) -> Unit,
) {
    val authorPrefix = authorPrefixFromSignedRoomMessage(message)
    if (authorPrefix == null) {
        logger?.warning(DROPPING_SIGNED_MESSAGE_INVALID_PREFIX)
        return
    }
    try {
        val saved = handle(authorPrefix)
        if (saved != null) onSaved(saved)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        logger?.error("$FAILED_TO_HANDLE_ROOM_MESSAGE: ${error.message ?: error}")
    }
}
