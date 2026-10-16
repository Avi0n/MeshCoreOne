package one.meshcore.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class DraftBackgroundObservation(
    private val activity: MainActivity,
    private val isForeground: () -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val dispatched = CountDownLatch(1)
    private val resumed = CountDownLatch(1)
    private var active = true
    private var eventCount = 0
    private var eventNanos = 0L
    private var afterDispatchNanos = 0L
    private var sameOwner = false
    private var stoppedAfterDispatch = false
    private var foregroundAfterDispatch = true
    private var postAccepted = false
    private val afterDispatch = Runnable {
        if (active) {
            afterDispatchNanos = SystemClock.elapsedRealtimeNanos()
            stoppedAfterDispatch = activity.lifecycle.currentState == Lifecycle.State.CREATED
            foregroundAfterDispatch = isForeground()
            dispatched.countDown()
        }
    }
    private val observer = LifecycleEventObserver { owner, event ->
        if (active && event == Lifecycle.Event.ON_STOP) {
            eventCount += 1
            if (eventCount == 1) {
                sameOwner = owner === activity
                eventNanos = SystemClock.elapsedRealtimeNanos()
                postAccepted = handler.post(afterDispatch)
            }
        } else if (active && event == Lifecycle.Event.ON_RESUME && eventCount > 0) {
            resumed.countDown()
        }
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(activity.lifecycle.currentState == Lifecycle.State.RESUMED)
        activity.lifecycle.addObserver(observer)
    }

    fun result(editNanos: Long, requestReturnedNanos: Long): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        val arrived = dispatched.await(DraftRetentionAppRootFixture.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val snapshot = AtomicReference<JSONObject>()
        val completed = CountDownLatch(1)
        val request = Runnable {
            val admission = afterDispatchNanos - editNanos
            snapshot.set(
                JSONObject().put(
                    "afterDispatchAwaitCompleted",
                    arrived,
                ).put("snapshotReadOnMain", true).put("eventCount", eventCount).put("sameOwner", sameOwner)
                    .put("postAccepted", postAccepted).put("afterDispatchObserved", afterDispatchNanos > 0)
                    .put("eventElapsedNanos", if (eventNanos > 0) eventNanos - editNanos else JSONObject.NULL)
                    .put("afterDispatchElapsedNanos", if (afterDispatchNanos > 0) admission else JSONObject.NULL)
                    .put(
                        "requestUpperBoundNanos",
                        requestReturnedNanos - editNanos,
                    ).put("stoppedAfterDispatch", stoppedAfterDispatch)
                    .put("foregroundAfterDispatch", foregroundAfterDispatch).put("actualOnStop", eventCount == 1)
                    .put("onStopBeforeDebounce", afterDispatchNanos > 0 && admission in 1 until DEBOUNCE_NS),
            )
            completed.countDown()
        }
        val accepted = handler.post(request)
        if (accepted && completed.await(DraftRetentionAppRootFixture.TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return checkNotNull(snapshot.get())
        }
        handler.removeCallbacks(request)
        return JSONObject().put("snapshotReadOnMain", false).put("snapshotRequestAccepted", accepted)
            .put("actualOnStop", false).put("onStopBeforeDebounce", false)
            .put("requestUpperBoundNanos", requestReturnedNanos - editNanos)
    }

    fun awaitResumed(): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        return resumed.await(DraftRetentionAppRootFixture.TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    fun awaitWindowReady(read: (MainActivity) -> JSONObject): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        val running = AtomicBoolean(true)
        val latest = AtomicReference<JSONObject>()
        val completed = CountDownLatch(1)
        val started = SystemClock.elapsedRealtime()
        var initial: JSONObject? = null
        var samples = 0
        val probe = object : Runnable {
            override fun run() {
                if (!running.get() || !active) return
                val snapshot = read(activity)
                if (initial == null) initial = snapshot
                samples += 1
                val elapsed = SystemClock.elapsedRealtime() - started
                latest.set(
                    JSONObject().put("initial", initial).put("final", snapshot).put("samples", samples)
                        .put("waitElapsedMs", elapsed),
                )
                if (snapshot.getBoolean("ready") || elapsed >= DraftRetentionAppRootFixture.TIMEOUT_MS) {
                    completed.countDown()
                } else {
                    handler.postDelayed(this, READINESS_POLL_MS)
                }
            }
        }
        val accepted = handler.post(probe)
        try {
            val settled = accepted && completed.await(DraftRetentionAppRootFixture.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val observation = latest.get()
            return (observation ?: JSONObject()).put("snapshotObserved", observation != null)
                .put("probeRequestAccepted", accepted).put("waitCompleted", settled)
        } finally {
            running.set(false)
            handler.removeCallbacks(probe)
        }
    }

    private val READINESS_POLL_MS get() = 25L

    private val DEBOUNCE_NS get() = DraftRetentionAppRootFixture.DEBOUNCE_NS

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        active = false
        activity.lifecycle.removeObserver(observer)
        handler.removeCallbacks(afterDispatch)
    }
}
