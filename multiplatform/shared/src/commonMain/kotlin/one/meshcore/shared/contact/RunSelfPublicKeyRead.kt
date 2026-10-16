package one.meshcore.shared.contact

import one.meshcore.shared.runExceptionFallback

/** An unavailable self key skips V-contact decisions; cancellation still stops the caller. */
internal suspend fun runSelfPublicKeyRead(read: suspend () -> ByteArray?): ByteArray? =
    runExceptionFallback({ null }, read)
