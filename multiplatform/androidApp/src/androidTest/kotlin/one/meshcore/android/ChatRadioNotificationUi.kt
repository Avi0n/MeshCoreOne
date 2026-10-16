package one.meshcore.android

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal class ChatRadioNotificationUi(private val stage: (String) -> Unit) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun tapPostedBody() {
        assertTrue(
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
        )
        var body: AccessibilityNodeInfo? = null
        await("synthetic notification body visible in SystemUI") {
            body = find { it.text?.toString() == ChatRadioNotificationData.NOTIFICATION_BODY }
            body != null
        }
        val observedBody = checkNotNull(body)
        assertEquals("com.android.systemui", observedBody.packageName?.toString())
        var clickable: AccessibilityNodeInfo? = observedBody
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        assertTrue("SystemUI body has a clickable notification ancestor", clickable != null)
        assertTrue(checkNotNull(clickable).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    fun assertThread(history: String, draft: String, sendLabel: String, enabled: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var previous: ThreadSnapshot? = null
        var observed = ThreadSnapshot.EMPTY
        var accepted: ThreadSnapshot? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            observed = threadSnapshot(history, draft, sendLabel)
            if (observed.matches(enabled) && observed == previous) {
                accepted = observed
                break
            }
            previous = observed.takeIf { it.matches(enabled) }
            Thread.sleep(POLL_MS)
        }
        val captured = accepted ?: observed
        val owner = "own-debug-${captured.ownDebugPackage}"
        val content = "history-${captured.historyVisible}-draft-${captured.draftVisible}"
        val send = "send-present-${captured.sendPresent}-send-${captured.sendEnabled}"
        stage("ui-$owner-$content-$send")
        assertTrue("actual AppRoot exposes cached history, draft and expected composer readiness", accepted != null)
        assertTrue(captured.ownDebugPackage)
        assertTrue(captured.historyVisible)
        assertTrue(captured.draftVisible)
        assertTrue(captured.sendPresent)
        assertEquals(enabled, captured.sendEnabled)
    }

    private fun threadSnapshot(history: String, draft: String, sendLabel: String): ThreadSnapshot {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return ThreadSnapshot.EMPTY
        var historyVisible = false
        var draftVisible = false
        var sendPresent = false
        var sendEnabled: Boolean? = null
        val remaining = ArrayDeque<AccessibilityNodeInfo>()
        remaining.add(root)
        while (remaining.isNotEmpty()) {
            val node = remaining.removeFirst()
            if (node.text?.toString()?.contains(history) == true) historyVisible = true
            if (node.text?.toString() == draft && node.isEditable) draftVisible = true
            if (node.contentDescription?.toString() == sendLabel) {
                sendPresent = true
                sendEnabled = node.isEnabled
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(remaining::addLast)
        }
        return ThreadSnapshot(
            ownDebugPackage = root.packageName?.toString() == DEBUG_PACKAGE,
            historyVisible = historyVisible,
            draftVisible = draftVisible,
            sendPresent = sendPresent,
            sendEnabled = sendEnabled,
        )
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val remaining = ArrayDeque<AccessibilityNodeInfo>()
        remaining.add(root)
        while (remaining.isNotEmpty()) {
            val node = remaining.removeFirst()
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(remaining::addLast)
        }
        return null
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(POLL_MS)
        }
        assertTrue(description, predicate())
    }

    private data class ThreadSnapshot(
        val ownDebugPackage: Boolean,
        val historyVisible: Boolean,
        val draftVisible: Boolean,
        val sendPresent: Boolean,
        val sendEnabled: Boolean?,
    ) {
        fun matches(enabled: Boolean): Boolean {
            if (!ownDebugPackage || !historyVisible || !draftVisible) return false
            return sendPresent && sendEnabled == enabled
        }

        companion object {
            val EMPTY = ThreadSnapshot(false, false, false, false, null)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
        const val POLL_MS = 25L
        const val DEBUG_PACKAGE = "io.pocketmesh.app.debug"
    }
}
