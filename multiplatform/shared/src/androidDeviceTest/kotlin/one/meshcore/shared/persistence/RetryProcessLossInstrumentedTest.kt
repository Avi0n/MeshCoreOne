package one.meshcore.shared.persistence

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.chat.DefaultSendQueueStore
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
class RetryProcessLossInstrumentedTest {
    @Test fun interruptActualRetryBeforeReplacementInsert() = phase(INTERRUPT)

    @Test fun verifyInterruptedRetryRolledBack() = phase(ROLLBACK)

    @Test fun actualLiveRetryCommits() = phase(LIVE)

    @Test fun verifyLiveRetryAfterFreshProcess() = phase(COMMITTED)

    private fun phase(stage: String) {
        val args = InstrumentationRegistry.getArguments()
        val selected = args.getString("retryStage")
        assumeTrue("Requires the isolated retry transaction host protocol", selected != null)
        require(selected == stage)
        val nonce = requireNotNull(args.getString("retryNonce"))
        require(nonce.matches(Regex("[a-f0-9]{12}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isWriter = stage == INTERRUPT || stage == LIVE
        val live = stage == LIVE || stage == COMMITTED
        val name = "retry-$nonce-${if (live) "live" else "interrupted"}.db"
        assertEquals(!isWriter, context.getDatabasePath(name).exists())
        if (!isWriter) validatePrior(context, nonce, name, if (live) LIVE else INTERRUPT)
        val driver = if (isWriter) {
            RetryProcessDriver {
                publish(context, nonce, name, stage + "-gate", held = !live)
                if (!live) {
                    CountDownLatch(1).await(HOLD_SECONDS, TimeUnit.SECONDS)
                    fail("The original transaction hold expired before host process loss")
                }
            }
        } else {
            null
        }
        val database = if (driver != null) {
            Room.databaseBuilder<MeshDatabase>(context, context.getDatabasePath(name).absolutePath)
                .setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).productionSchemaPolicy().build()
        } else {
            meshDatabase(context, Dispatchers.IO, name)
        }
        try {
            runBlocking {
                withTimeout(if (stage == INTERRUPT) INTERRUPT_TIMEOUT_MS else ASSERT_TIMEOUT_MS) {
                    if (driver == null) {
                        RetryProcessRows.verify(database, committed = live)
                    } else {
                        RetryProcessRows.seed(database)
                        val chat =
                            RoomChatStore(
                                RetryProcessRows.radioA,
                                database.chatDaos(),
                                RxLogCorrelator(database.rxLogEntryDao()),
                            )
                        val store = DefaultSendQueueStore(RoomPendingSendStore(database.pendingSendDao()), chat) {
                            error("Retry persistence must not look up contacts")
                        }
                        driver.arm()
                        val sequence = store.replacePendingSendForRetry(
                            RetryProcessRows.messageID,
                            RetryProcessRows.replacement,
                        )
                        assertEquals(RetryProcessRows.NEXT_SEQUENCE, sequence)
                        assertEquals(1, driver.deleteHits)
                        assertEquals(1, driver.insertHits)
                        RetryProcessRows.verify(database, committed = true)
                    }
                }
            }
            assertTrue(stage != INTERRUPT)
            publish(context, nonce, name, stage, held = false)
        } finally {
            database.close()
        }
    }

    private fun validatePrior(context: Context, nonce: String, name: String, prior: String) {
        val suffix = if (prior == INTERRUPT) "-gate" else ""
        val receipt = JSONObject(File(context.filesDir, "retry-$nonce-$prior$suffix.json").readText())
        assertEquals(prior + suffix, receipt.getString("phase"))
        assertEquals(nonce, receipt.getString("nonce"))
        assertEquals(name, receipt.getString("database"))
        assertEquals(Process.myUid(), receipt.getInt("uid"))
        assertTrue(Process.myPid() != receipt.getInt("pid"))
        assertTrue(receipt.getBoolean("assertionsComplete"))
    }

    private fun publish(context: Context, nonce: String, name: String, stage: String, held: Boolean) {
        val receipt = File(context.filesDir, "retry-$nonce-$stage.json")
        val temporary = File(context.filesDir, receipt.name + ".tmp")
        assertFalse(receipt.exists() || temporary.exists())
        val gate = stage.endsWith("-gate")
        val json = JSONObject().put("phase", stage).put("nonce", nonce).put("database", name)
            .put("pid", Process.myPid()).put("uid", Process.myUid()).put("assertionsComplete", true)
            .put("actualTransactionPartialState", gate).put("databaseOpen", true)
            .put(
                "holdDeadlineElapsedMs",
                if (held) SystemClock.elapsedRealtime() + HOLD_SECONDS * MILLIS_PER_SECOND else 0,
            )
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        assertTrue(temporary.renameTo(receipt))
    }

    private companion object {
        const val INTERRUPT = "interrupt"
        const val ROLLBACK = "rollback"
        const val LIVE = "live"
        const val COMMITTED = "committed"
        const val HOLD_SECONDS = 180L
        const val MILLIS_PER_SECOND = 1_000L
        const val ASSERT_TIMEOUT_MS = 30_000L
        const val INTERRUPT_TIMEOUT_MS = 240_000L
    }
}
