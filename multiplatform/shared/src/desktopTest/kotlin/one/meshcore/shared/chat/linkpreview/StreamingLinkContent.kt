package one.meshcore.shared.chat.linkpreview

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

internal class StreamingLinkContent(private val byteCount: Int) {
    data class Terminal(val emittedBytes: Int, val finishedBody: Boolean)

    val termination = CompletableDeferred<Terminal>()

    fun start(context: CoroutineContext): ByteReadChannel {
        val channel = ByteChannel(autoFlush = true)
        CoroutineScope(context).launch {
            var emitted = 0
            val finished = runCatching {
                while (emitted < byteCount) {
                    delay(1)
                    val chunk = ByteArray(minOf(64 * 1024, byteCount - emitted))
                    if (emitted == 0) LINK_CONTENT_PNG.copyInto(chunk)
                    channel.writeFully(chunk)
                    emitted += chunk.size
                }
                true
            }.getOrDefault(false)
            channel.close()
            termination.complete(Terminal(emitted, finished))
        }
        return channel
    }
}
