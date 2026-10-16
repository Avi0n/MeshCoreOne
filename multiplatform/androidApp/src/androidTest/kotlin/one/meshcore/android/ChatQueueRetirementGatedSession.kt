package one.meshcore.android

import kotlinx.coroutines.CompletableDeferred
import one.meshcore.shared.meshcore.MeshSession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class ChatQueueRetirementGatedSession(private val delegate: MeshSession) : MeshSession by delegate {
    val stopEntered = CompletableDeferred<Unit>()
    val releaseStop = CompletableDeferred<Unit>()
    val stopCalls = AtomicInteger()
    val freshFloodCalls = CopyOnWriteArrayList<FloodCall>()

    override suspend fun setFloodScope(scopeType: String, scopeText: String, scopeKey: ByteArray) {
        freshFloodCalls.add(FloodCall(scopeType, scopeText, scopeKey.size))
        delegate.setFloodScope(scopeType, scopeText, scopeKey)
    }

    override suspend fun setFloodScopeUnscoped() {
        freshFloodCalls.add(FloodCall("unscoped", "", 0))
        delegate.setFloodScopeUnscoped()
    }

    override suspend fun stopAutoMessageFetching() {
        stopCalls.incrementAndGet()
        stopEntered.complete(Unit)
        releaseStop.await()
    }

    data class FloodCall(val type: String, val text: String, val keyLength: Int)
}
