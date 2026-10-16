package one.meshcore.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot or Play update the process is gone and START_STICKY does not fire. iOS
 * restores the BLE session from `willRestoreState`. This start brings the last wanted radio
 * back without a tap. Activate still skips a user-initiated disconnect.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldStartConnectionAfterBoot(intent.action)) return
        ConnectionService.startAfterBoot(context)
    }
}
