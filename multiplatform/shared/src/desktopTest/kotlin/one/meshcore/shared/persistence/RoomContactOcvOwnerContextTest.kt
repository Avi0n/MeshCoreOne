package one.meshcore.shared.persistence

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.contact.updateContactOCVSettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomContactOcvOwnerContextTest {
    @Test fun ownerPredicateStaysOnTheOriginatingCallerThread() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val rows = RoomContactDeleteAtomicityFixture()
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    rows.seed()
                    val before = rows.snapshot()
                    val contacts = rows.database.contactDao().listAll().sortedBy { it.id.value }.map {
                        if (it.id ==
                            rows.firstContact
                        ) {
                            it.copy(ocvPreset = PRESET, customOCVArrayString = CURVE).toDto()
                        } else {
                            it.toDto()
                        }
                    }
                    val expected = JsonObject(
                        before.first.toMutableMap().apply {
                            put("contacts", JSON.encodeToJsonElement(contacts))
                        },
                    ) to before.second
                    val callerThread = Thread.currentThread()
                    val predicateThreads = CopyOnWriteArrayList<Thread>()
                    testContactService(store = rows.store).updateContactOCVSettings(
                        rows.firstContact,
                        PRESET,
                        CURVE,
                        isCurrent = {
                            predicateThreads += Thread.currentThread()
                            true
                        },
                    )
                    assertEquals(expected, rows.snapshot())
                    assertEquals(2, predicateThreads.size)
                    assertTrue(
                        predicateThreads.all { it === callerThread },
                        "LAST: OCV owner predicate left its originating caller thread",
                    )
                }
            }
        } finally {
            try {
                rows.close()
            } finally {
                dispatcher.close()
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val PRESET = "caller-owned curve"
        private const val CURVE = "[3.2,3.5,3.9]"
        private val JSON = Json { encodeDefaults = true }
    }
}
