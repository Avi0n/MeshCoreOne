package one.meshcore.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StoredRadioSeedTest {
    @Test
    fun `a radio replacement during load suppresses the old value`() = runTest {
        val firstRadio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val secondRadio = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
        var currentRadio = firstRadio
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()

        val result = async {
            publishStoredRadioValueIfCurrent(
                seedRadio = firstRadio,
                load = {
                    loadStarted.complete(Unit)
                    releaseLoad.await()
                    "first-radio channels"
                },
                currentRadio = { currentRadio },
                publish = published::add,
            )
        }

        loadStarted.await()
        currentRadio = secondRadio
        releaseLoad.complete(Unit)

        assertFalse(result.await())
        assertEquals(emptyList<String>(), published)
    }
}
