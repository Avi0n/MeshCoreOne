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

internal class AppBleColdCheckpoints(val phase: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    val nonce = checkNotNull(arguments.getString(ARGUMENT))
    val handle = checkNotNull(arguments.getString(HANDLE_ARGUMENT))
    val keyHash = checkNotNull(arguments.getString(KEY_ARGUMENT))
    private val context = instrumentation.targetContext
    private val directory = File(context.filesDir, "ble-cold-$nonce")

    init {
        check(context.packageName == PACKAGE)
        check(arguments.getString(PHASE_ARGUMENT) == phase)
        check(arguments.getString("class") == "$CLASS#${METHODS.getValue(phase)}")
        require(Regex("[0-9a-f]{32}").matches(nonce))
        require(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}").matches(handle))
        require(Regex("[0-9a-f]{64}").matches(keyHash))
        if (phase == "seed") {
            check(!directory.exists() && directory.mkdir())
        } else {
            check(directory.isDirectory)
        }
    }

    fun seed(): JSONObject {
        val original = JSONObject(File(directory, "seed-held.json").readText())
        check(original.getString("nonce") == nonce)
        check(original.getString("stage") == "seed-held")
        check(original.getString("phase") == "seed")
        check(original.getInt("uid") == Process.myUid())
        check(original.getInt("pid") > 0 && original.getInt("pid") != Process.myPid())
        check(original.getBoolean("actualDefaultApplication"))
        check(!original.getBoolean("graphReplaced"))
        check(original.getString("publicKeySha256") == keyHash)
        check(original.getInt("pendingCount") == 0 && original.getInt("outgoingCount") == 0)
        check(original.getInt("selfRoomCount") == 0)
        val recordedAt = original.getLong("elapsedRealtimeMs")
        val heldUntil = original.getLong("heldUntilElapsedMs")
        check(recordedAt > 0 && recordedAt <= SystemClock.elapsedRealtime())
        check(heldUntil > recordedAt && heldUntil - recordedAt <= HOLD_MS)
        return original
    }

    fun assertPreviousDisconnect() {
        check(phase == "suppressed")
        val original = seed()
        val previous = JSONObject(File(directory, "reconnect-disconnected.json").readText())
        for (key in listOf("nonce", "deviceId", "radioId", "publicKeySha256")) {
            check(previous.getString(key) == original.getString(key))
        }
        check(previous.getString("stage") == "reconnect-disconnected")
        check(previous.getString("phase") == "reconnect")
        check(previous.getInt("uid") == Process.myUid())
        check(previous.getInt("pid") > 0 && previous.getInt("pid") != Process.myPid())
        check(previous.getInt("pid") != original.getInt("pid"))
        check(previous.getLong("elapsedRealtimeMs") >= original.getLong("elapsedRealtimeMs"))
        check(previous.getLong("elapsedRealtimeMs") <= SystemClock.elapsedRealtime())
        check(previous.getBoolean("actualDefaultApplication") && !previous.getBoolean("graphReplaced"))
        check(previous.getInt("pendingCount") == 0 && previous.getInt("outgoingCount") == 0)
        check(previous.getInt("selfRoomCount") == 0)
    }

    suspend fun publish(stage: String, deviceId: String, radioId: String) {
        val record = record(stage, deviceId, radioId, null)
        write(stage, record)
        val ack = File(directory, "$stage.ack")
        withTimeout(ACK_TIMEOUT_MS) {
            while (!ack.exists()) delay(POLL_MS)
        }
        check(ack.readBytes().contentEquals("$nonce:$phase:$stage\n".toByteArray(Charsets.US_ASCII)))
    }

    suspend fun hold(deviceId: String, radioId: String) {
        write("seed-held", record("seed-held", deviceId, radioId, SystemClock.elapsedRealtime() + HOLD_MS))
        delay(HOLD_MS)
        error("Seed hold expired before the qualified host stop")
    }

    private fun record(stage: String, deviceId: String, radioId: String, heldUntil: Long?): JSONObject =
        JSONObject().put("nonce", nonce).put("stage", stage).put("phase", phase)
            .put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("heldUntilElapsedMs", heldUntil ?: JSONObject.NULL)
            .put("actualDefaultApplication", true).put("graphReplaced", false)
            .put("deviceId", deviceId).put("radioId", radioId).put("publicKeySha256", keyHash)
            .put("pendingCount", 0).put("outgoingCount", 0).put("selfRoomCount", 0)

    private fun write(stage: String, record: JSONObject) {
        val target = File(directory, "$stage.json")
        val temporary = File(directory, "$stage.writing")
        check(!target.exists() && !temporary.exists())
        FileOutputStream(temporary).use {
            it.write(record.toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val PACKAGE = "io.pocketmesh.app.blecold"
        const val ARGUMENT = "mc1BleColdNonce"
        const val PHASE_ARGUMENT = "mc1BleColdPhase"
        const val HANDLE_ARGUMENT = "mc1BleColdHandle"
        const val KEY_ARGUMENT = "mc1BleColdPublicKeySha256"
        const val CLASS = "one.meshcore.android.AppBleColdInstrumentedTest"
        const val POLL_MS = 25L
        const val ACK_TIMEOUT_MS = 60_000L
        const val HOLD_MS = 90_000L
        val METHODS = mapOf(
            "seed" to "seedSavedRadioAndHoldConnected",
            "reconnect" to "freshProcessAutomaticallyReconnectsSavedRadio",
            "suppressed" to "explicitDisconnectSuppressesFreshLaunchReconnect",
        )
    }
}
