// AndroidOnly: WP-407 Direct-boot entry points must not open credential-protected stores before unlock.
package com.meshcoreone.android.app.platform

import java.util.concurrent.atomic.AtomicBoolean

internal class UnlockedProcessStart(private val start: () -> Unit) {
    private val started = AtomicBoolean(false)

    fun onUnlockState(unlocked: Boolean) {
        if (unlocked && started.compareAndSet(false, true)) start()
    }
}
