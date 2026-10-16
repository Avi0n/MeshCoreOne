package one.meshcore.android

import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class AppBleQueueCheckpoints {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    val nonce = checkNotNull(arguments.getString(ARGUMENT))
    val handle = checkNotNull(arguments.getString(HANDLE_ARGUMENT))
    val keyHash = checkNotNull(arguments.getString(KEY_ARGUMENT))
    private val context = instrumentation.targetContext
    private val directory = File(context.filesDir, "ble-queue-$nonce")
    private var sequence = 0

    init {
        check(context.packageName == PACKAGE)
        check(arguments.getString("class") == CLASS_METHOD)
        check(arguments.getString(PHASE_ARGUMENT) == "empty-lifecycle")
        require(Regex("[0-9a-f]{32}").matches(nonce))
        require(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}").matches(handle))
        require(Regex("[0-9a-f]{64}").matches(keyHash))
        check(!directory.exists())
        check(directory.mkdir())
    }

    suspend fun publish(stage: String) {
        check(STAGES[sequence] == stage)
        val current = ++sequence
        val record = JSONObject().put("nonce", nonce).put("stage", stage).put("sequence", current)
            .put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("actualDefaultApplication", true).put("graphReplaced", false)
            .put("pendingCount", 0).put("outgoingCount", 0).put("selfRoomCount", 0)
            .put("connectionEntry", "AppContainer.connect").put("disconnectEntry", "AppRoot UI")
            .put("privateReplayProved", false)
        val target = File(directory, "$current-$stage.json")
        val temporary = File(directory, "$current-$stage.writing")
        check(!target.exists() && !temporary.exists())
        FileOutputStream(temporary).use {
            it.write(record.toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        val ack = File(directory, "$current-$stage.ack")
        withTimeout(ACK_TIMEOUT_MS) {
            while (!ack.exists()) delay(POLL_MS)
        }
        val expected = "$nonce:$current:$stage\n".toByteArray(Charsets.UTF_8)
        check(ack.readBytes().contentEquals(expected))
    }

    fun publishDisconnectFailure(record: JSONObject) {
        record.put("nonce", nonce).put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_DIAGNOSTIC_BYTES)
        val target = File(directory, "disconnect-failure.json")
        val temporary = File(directory, "disconnect-failure.writing")
        check(!target.exists() && !temporary.exists())
        FileOutputStream(temporary).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val PACKAGE = "io.pocketmesh.app.queueble"
        const val ARGUMENT = "mc1BleQueueNonce"
        const val PHASE_ARGUMENT = "mc1BleQueuePhase"
        const val HANDLE_ARGUMENT = "mc1BleQueueHandle"
        const val KEY_ARGUMENT = "mc1BleQueuePublicKeySha256"
        const val CLASS_METHOD =
            "one.meshcore.android.AppBleQueueInstrumentedTest#defaultBleQueueRecreatesRetiresAndReplaces"
        const val POLL_MS = 25L
        const val ACK_TIMEOUT_MS = 60_000L
        private const val MAX_DIAGNOSTIC_BYTES = 4096
        val STAGES = listOf("offline", "first-ready", "recreated", "first-disconnected", "second-ready", "complete")
    }
}
