package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.settings.PersistentLogger

/** A failed status write can be reconciled on reload; cancellation must stop subsequent UI updates. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runChatThreadStatusWrite(scope: CoroutineScope, block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        PersistentLogger(scope, category = "ChatThreadViewModel")
            .warning("Chat status write failed: ${error::class.simpleName}")
    }
}
