package one.meshcore.shared.persistence

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PendingSendProcessLossInstrumentedTest {
    @Test
    fun seedCommittedRowsAndHoldOpen() = phase(SEED, held = true) { db, store ->
        assertTrue(store.fetchPendingSends(PendingSendProcessRows.radioA).isEmpty())
        assertTrue(store.fetchPendingSends(PendingSendProcessRows.radioB).isEmpty())
        PendingSendProcessRows.seed(db, store)
        PendingSendProcessRows.verify(db, store, deleted = false)
    }

    @Test
    fun verifyRestoredRowsDeleteChannelAndHoldOpen() = phase(DELETE, held = true) { db, store ->
        PendingSendProcessRows.verify(db, store, deleted = false)
        store.deletePendingSend(PendingSendProcessRows.channelID)
        PendingSendProcessRows.verify(db, store, deleted = true)
    }

    @Test
    fun verifyDeletionAndSurvivingRows() = phase(VERIFY, held = false) { db, store ->
        PendingSendProcessRows.verify(db, store, deleted = true)
    }

    private fun phase(stage: String, held: Boolean, assertions: suspend (MeshDatabase, RoomPendingSendStore) -> Unit) {
        val arguments = InstrumentationRegistry.getArguments()
        val selected = arguments.getString("pendingStage")
        assumeTrue("Requires the isolated three-process host protocol", selected != null)
        require(selected == stage)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nonce = requireNotNull(arguments.getString("pendingNonce"))
        require(nonce.matches(Regex("[a-f0-9]{12}")))
        val name = "pending-process-$nonce.db"
        val databaseFile = context.getDatabasePath(name)
        assertEquals(stage != SEED, databaseFile.exists())
        val receipt = File(context.filesDir, "pending-$nonce-$stage.json")
        assertFalse(receipt.exists())
        if (stage != SEED) {
            val previous = if (stage == DELETE) SEED else DELETE
            val prior = JSONObject(File(context.filesDir, "pending-$nonce-$previous.json").readText())
            assertEquals(previous, prior.getString("phase"))
            assertEquals(nonce, prior.getString("nonce"))
            assertEquals(name, prior.getString("database"))
            assertEquals(Process.myUid(), prior.getInt("uid"))
            assertTrue(Process.myPid() != prior.getInt("pid"))
            assertTrue(prior.getBoolean("assertionsComplete") && prior.getBoolean("databaseOpen"))
        }
        val database = meshDatabase(context, Dispatchers.IO, name)
        try {
            runBlocking {
                withTimeout(ASSERT_TIMEOUT_MS) { assertions(database, RoomPendingSendStore(database.pendingSendDao())) }
            }
            val now = SystemClock.elapsedRealtime()
            val record = JSONObject()
                .put("phase", stage)
                .put("nonce", nonce)
                .put("database", name)
                .put("pid", Process.myPid())
                .put("uid", Process.myUid())
                .put("assertionsComplete", true)
                .put("databaseOpen", true)
                .put("holdDeadlineElapsedMs", if (held) now + HOLD_SECONDS * MILLIS_PER_SECOND else 0)
            val temporary = File(context.filesDir, receipt.name + ".tmp")
            assertFalse(temporary.exists())
            FileOutputStream(temporary).use { output ->
                output.write(record.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            assertTrue(temporary.renameTo(receipt))
            if (held) {
                CountDownLatch(1).await(HOLD_SECONDS, TimeUnit.SECONDS)
                fail("Host did not stop this process before the database-open hold expired")
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val SEED = "seed"
        const val DELETE = "delete"
        const val VERIFY = "verify"
        const val ASSERT_TIMEOUT_MS = 30_000L
        const val HOLD_SECONDS = 180L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
