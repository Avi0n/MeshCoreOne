package one.meshcore.android

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.onboarding_welcome_title
import one.meshcore.shared.resources.settings_backup_import_cancelled_title
import one.meshcore.shared.resources.settings_backup_import_preview_button
import one.meshcore.shared.resources.settings_backup_import_preview_cancel
import one.meshcore.shared.resources.settings_backup_import_preview_title
import one.meshcore.shared.resources.settings_backup_import_success_title
import one.meshcore.shared.resources.settings_backup_import_title
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertTrue
import java.io.File

internal class BackupPostcommitUi {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    lateinit var welcome: String
    lateinit var importFile: String
    lateinit var preview: String
    lateinit var confirm: String
    lateinit var cancel: String
    lateinit var cancelled: String
    lateinit var success: String

    suspend fun loadLabels() {
        welcome = getString(Res.string.onboarding_welcome_title)
        importFile = getString(Res.string.settings_backup_import_title)
        preview = getString(Res.string.settings_backup_import_preview_title)
        confirm = getString(Res.string.settings_backup_import_preview_button)
        cancel = getString(Res.string.settings_backup_import_preview_cancel)
        cancelled = getString(Res.string.settings_backup_import_cancelled_title)
        success = getString(Res.string.settings_backup_import_success_title)
    }

    fun visible(text: String): Boolean = find { it.isVisibleToUser && it.text?.toString() == text } != null

    fun await(description: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < until) {
            if (predicate()) return
            Thread.sleep(POLL_MS)
        }
        assertTrue(description, predicate())
    }

    fun click(text: String) {
        await("Visible clickable backup control") {
            var node = find { it.isVisibleToUser && it.text?.toString() == text }
            while (node != null && !node.isClickable) node = node.parent
            node?.let { it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
    }

    fun screenshot(file: File) {
        checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            try {
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
        val root = automation.rootInActiveWindow ?: return null
        val remaining = ArrayDeque<AccessibilityNodeInfo>()
        remaining.add(root)
        while (remaining.isNotEmpty()) {
            val node = remaining.removeFirst()
            if (node.packageName?.toString() == DEBUG_PACKAGE && predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(remaining::addLast)
        }
        return null
    }

    companion object {
        const val DEBUG_PACKAGE = "io.pocketmesh.app.debug"
        private const val TIMEOUT_MS = 12_000L
        private const val POLL_MS = 25L
        private const val PNG_QUALITY = 100
    }
}
