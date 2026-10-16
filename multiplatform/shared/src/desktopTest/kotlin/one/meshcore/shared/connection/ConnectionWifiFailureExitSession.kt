package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import one.meshcore.shared.meshcore.MeshContact
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

internal class ConnectionWifiFailureExitSession(publicKey: ByteArray) :
    FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) {
    val contactEntered = CompletableDeferred<Job>()
    val stopCompleted = CompletableDeferred<CoroutineContext>()
    val stopRelease = CompletableDeferred<Unit>()
    private val armed = AtomicBoolean(false)

    init {
        contactsGate = CompletableDeferred()
    }

    override suspend fun getContacts(since: Long?): List<MeshContact> {
        contactEntered.complete(currentCoroutineContext().job)
        return super.getContacts(since)
    }

    fun armStop() {
        armed.set(true)
    }

    override suspend fun stopAutoMessageFetching() {
        super.stopAutoMessageFetching()
        if (armed.compareAndSet(true, false)) {
            stopCompleted.complete(currentCoroutineContext())
            stopRelease.await()
        }
    }

    fun releaseAll() {
        contactsGate?.complete(Unit)
        stopRelease.complete(Unit)
    }

    companion object {
        const val ORIGIN_NAME = "ordinary-wifi-failure-exit"
    }
}
