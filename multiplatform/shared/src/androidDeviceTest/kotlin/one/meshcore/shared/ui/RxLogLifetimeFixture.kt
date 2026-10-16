package one.meshcore.shared.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.RoomRxLogStore
import one.meshcore.shared.persistence.RxLogEntryDao
import one.meshcore.shared.persistence.toEntity
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogStore
import java.util.concurrent.atomic.AtomicInteger

internal class RxLogLifetimeFixture(room: NodeRemovalFixture, scope: CoroutineScope, val radioID: Uuid) {
    val release = CompletableDeferred<Unit>()

    @Volatile var readStarted = false
        private set

    @Volatile var readCancelled = false
        private set
    private val dao = object : RxLogEntryDao by room.rxLogDao {
        override suspend fun listRadioIds(): List<Uuid> {
            readStarted = true
            try {
                release.await()
            } catch (cancelled: CancellationException) {
                readCancelled = true
                throw cancelled
            }
            return room.rxLogDao.listRadioIds()
        }
    }
    val durable = RoomRxLogStore(dao, scope)
    private val observers = AtomicInteger()
    val observerCount: Int get() = observers.get()

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    val store = object : RxLogStore by durable {
        override val snapshot = object : StateFlow<List<RxLogEntry>> by durable.snapshot {
            override suspend fun collect(collector: FlowCollector<List<RxLogEntry>>): Nothing {
                observers.incrementAndGet()
                try {
                    durable.snapshot.collect(collector)
                } finally {
                    observers.decrementAndGet()
                }
            }
        }
    }

    fun dependencies(base: ShellDependencies) = ShellDependencies(
        session = base.session,
        services = base.services.copy(rxLogStore = store, currentRadioId = { radioID }),
        sharing = base.sharing,
        preferences = base.preferences,
        radio = base.radio,
        maintenance = base.maintenance,
    )

    companion object {
        suspend fun seed(room: NodeRemovalFixture, radioID: Uuid, hash: String) {
            room.rxLogDao.upsert(entry(radioID, hash).toEntity())
        }

        fun entry(radioID: Uuid, hash: String) = RxLogEntry(
            id = Uuid.random(), radioID = radioID, receivedAtEpochMs = 1_000L,
            snr = null, rssi = null, routeType = 0, payloadType = 0, pathLength = 0,
            packetHash = hash, packetPayloadSize = 0,
        )
    }
}
