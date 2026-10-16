package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import one.meshcore.shared.transport.MeshLink
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

internal class ConnectionBleFailureExitLink(private val delegate: FakeMeshLink, private val onConnect: (Job) -> Unit) :
    MeshLink by delegate {
    val disconnected = CompletableDeferred<CoroutineContext>()
    val release = CompletableDeferred<Unit>()
    private val armed = AtomicBoolean(true)

    override suspend fun connect() {
        onConnect(currentCoroutineContext().job)
        delegate.connect()
    }

    override suspend fun disconnect() {
        delegate.disconnect()
        if (armed.compareAndSet(true, false)) {
            disconnected.complete(currentCoroutineContext())
            release.await()
        }
    }
}
