package one.meshcore.android

import one.meshcore.shared.AppInfo
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoUsageTest {
    @Test
    fun summaryStartsWithAppName() {
        assertTrue(AppInfo.summarize("Android").startsWith(AppInfo.APP_NAME))
    }
}
