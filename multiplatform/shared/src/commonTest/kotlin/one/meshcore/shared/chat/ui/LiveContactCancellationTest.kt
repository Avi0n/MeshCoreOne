package one.meshcore.shared.chat.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveContactCancellationTest {
    @Test
    fun cancelledNonCooperativeLookupCannotOverwriteAReplacementContact() = runTest {
        var current = "Original"
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lookup = launch {
            collectLiveContact(
                LiveContactLoad(
                    contact = "Original",
                    id = Uuid.random(),
                    current = { current },
                    load = {
                        withContext(NonCancellable) {
                            started.complete(Unit)
                            release.await()
                            "Obsolete"
                        }
                    },
                    invalidations = emptyFlow(),
                    assign = { current = requireNotNull(it) },
                ),
            )
        }
        started.await()
        lookup.cancel()
        current = "Replacement"
        release.complete(Unit)
        lookup.join()
        assertEquals("Replacement", current)
    }

    @Test
    fun cancelledRefreshSignalCannotOverwriteAReplacementContact() = runTest {
        var current = "Original"
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lookup = launch {
            refreshLiveContact(
                id = Uuid.random(),
                signal = 1,
                load = {
                    withContext(NonCancellable) {
                        started.complete(Unit)
                        release.await()
                        "Obsolete"
                    }
                },
                assign = { current = it },
            )
        }
        started.await()
        lookup.cancel()
        current = "Replacement"
        release.complete(Unit)
        lookup.join()
        assertEquals("Replacement", current)
    }
}
