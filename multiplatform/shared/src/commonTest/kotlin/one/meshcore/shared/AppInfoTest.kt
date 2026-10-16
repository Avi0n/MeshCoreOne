package one.meshcore.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test
    fun summarizeIncludesAppNameAndPlatform() {
        assertEquals("MeshCore One on TestPlatform", AppInfo.summarize("TestPlatform"))
    }

    @Test
    fun buildInfoRoundTripsThroughJson() {
        val original = AppBuildInfo(app = AppInfo.APP_NAME, platform = "TestPlatform")
        val decoded = AppInfo.decode(AppInfo.encode(original))
        assertEquals(original, decoded)
    }
}
