// AndroidOnly: WP-503 Execute the unchanged actual ChatTimeline/MessageBody all-theme painted contrast audit on Android.
package com.meshcoreone.android.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMessagingContrastAccessibilityTest : DeviceAccessibilityHarness() {
    @Test fun renderedDirectAndChannelSpansClearTheirPaintedBubblesInAll38EffectiveFrames() {
        auditMessagingContrast(compose, ::content, { AuditSurface(it) },
            ::textLayout, ::assertFont200Unclipped, ::paintedBackground)
    }
}
