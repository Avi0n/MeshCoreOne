package one.meshcore.shared.pathedit.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `AddHopPickerView.bannerText` after append / bulk add. */
class AddHopBannerAnnounceTest {
    @Test
    fun afterAppendAnnouncesTheNextHopOrMaxReached() {
        assertEquals(
            "Adding as hop 3",
            addHopBannerAnnouncement(
                pathSize = 2,
                hopLimit = 64,
                positionAppendTemplate = "Adding as hop %d",
                maxHopsReached = "Max hops reached",
            ),
        )
        assertEquals(
            "Max hops reached",
            addHopBannerAnnouncement(
                pathSize = 64,
                hopLimit = 64,
                positionAppendTemplate = "Adding as hop %d",
                maxHopsReached = "Max hops reached",
            ),
        )
        assertEquals(
            "Adding as hop 5",
            addHopBannerAnnouncement(
                pathSize = 4,
                hopLimit = null,
                positionAppendTemplate = "Adding as hop %d",
                maxHopsReached = "Max hops reached",
            ),
        )
    }
}
