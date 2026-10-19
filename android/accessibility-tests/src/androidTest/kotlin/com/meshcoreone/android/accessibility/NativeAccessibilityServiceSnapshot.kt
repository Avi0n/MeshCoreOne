// AndroidOnly: WP-503 Public native accessibility system/client snapshots; diagnostic collection never supplies acceptance.
package com.meshcoreone.android.accessibility

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.test.platform.app.InstrumentationRegistry

internal fun snapshotNativeServiceState(
    manager: AccessibilityManager,
    phase: String,
    afterOriginalDeadline: Boolean,
) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val start = SystemClock.elapsedRealtime()
    println("WP503_NATIVE_SERVICE_SNAPSHOT_START|phase=$phase|afterOriginalDeadline=$afterOriginalDeadline" +
        "|managerEnabled=${manager.isTouchExplorationEnabled}|flags=${requireNotNull(automation.serviceInfo).flags}")
    val descriptors = automation.executeShellCommandRw("sh")
    check(descriptors.size == 2) { "Public shell stream API must return stdout and stdin" }
    val script = "dumpsys -t 5 accessibility 2>&1\n" +
        "result=${'$'}?\n" +
        "printf '\\nWP503_NATIVE_SERVICE_DUMP_EXIT=%s\\n' \"${'$'}result\"\n"
    val output = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { stdout ->
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { stdin ->
            stdin.write(script.toByteArray(Charsets.UTF_8))
        }
        stdout.readBytes().toString(Charsets.UTF_8)
    }
    val lines = output.lineSequence().toList()
    for (line in lines.filter { it.isNotBlank() && !it.startsWith("WP503_NATIVE_SERVICE_DUMP_EXIT=") }) {
        println("WP503_NATIVE_SERVICE_SYSTEM|phase=$phase|$line")
    }
    val exits = lines.filter { it.startsWith("WP503_NATIVE_SERVICE_DUMP_EXIT=") }
    println("WP503_NATIVE_SERVICE_DUMP_MARKERS|phase=$phase|actual=${exits.size}|expected=1")
    check(exits.size == 1) { "Public shell did not return exactly one actual dump exit marker" }
    val exit = exits.single()
    println("WP503_NATIVE_SERVICE_DUMP_RESULT|phase=$phase|$exit")
    check(exit == "WP503_NATIVE_SERVICE_DUMP_EXIT=0") { "Public accessibility system snapshot failed: $exit" }
    println("WP503_NATIVE_SERVICE_SNAPSHOT_END|phase=$phase" +
        "|managerEnabled=${manager.isTouchExplorationEnabled}|flags=${requireNotNull(automation.serviceInfo).flags}" +
        "|durationMs=${SystemClock.elapsedRealtime() - start}|physicalTalkBack=false")
}
