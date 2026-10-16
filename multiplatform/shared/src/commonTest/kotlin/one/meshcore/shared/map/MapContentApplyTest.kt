package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pin-render decision, the Kotlin half of iOS's deferred source creation. The load of a remote
 * basemap style outlives the database read that produces the pins, so the ordering here decides whether
 * a map ever shows a node.
 */
class MapContentApplyTest {
    private val styleUrl = "https://tiles.example/style.json"
    private val otherStyleUrl = "https://tiles.example/dark.json"
    private val contact = MapPoint(
        id = "contact-1",
        coordinate = LatLng(37.33, -122.03),
        pinStyle = PinStyle.CONTACT_CHAT,
        label = "Base",
        isClusterable = true,
    )

    private fun content(points: List<MapPoint> = emptyList(), url: String = styleUrl) =
        MapContent(styleUrl = url, points = points, lines = emptyList())

    @Test
    fun firstPassLoadsTheStyle() {
        assertEquals(
            MapContentApplyAction.LOAD_STYLE,
            mapContentApplyAction(
                requested = content(),
                applied = AppliedMapContent(),
                hasLiveStyle = false,
                hasFramed = false,
            ),
        )
    }

    @Test
    fun pointsArrivingWhileTheStyleLoadsAreNotClaimedAsApplied() {
        assertEquals(
            MapContentApplyAction.AWAIT_STYLE,
            mapContentApplyAction(
                requested = content(points = listOf(contact)),
                applied = AppliedMapContent(styleUrl = styleUrl),
                hasLiveStyle = false,
                hasFramed = false,
            ),
        )
    }

    @Test
    fun theSamePointsApplyOnceTheStyleIsLive() {
        assertEquals(
            MapContentApplyAction.APPLY_CONTENT,
            mapContentApplyAction(
                requested = content(points = listOf(contact)),
                applied = AppliedMapContent(styleUrl = styleUrl),
                hasLiveStyle = true,
                hasFramed = false,
            ),
        )
    }

    @Test
    fun appliedContentIsLeftAlone() {
        assertEquals(
            MapContentApplyAction.SKIP,
            mapContentApplyAction(
                requested = content(points = listOf(contact)),
                applied = AppliedMapContent(styleUrl = styleUrl, points = listOf(contact), lines = emptyList()),
                hasLiveStyle = true,
                hasFramed = true,
            ),
        )
    }

    @Test
    fun renamingANodeRedrawsIt() {
        val renamed = contact.copy(label = "Ridge Repeater")
        assertEquals(
            MapContentApplyAction.APPLY_CONTENT,
            mapContentApplyAction(
                requested = content(points = listOf(renamed)),
                applied = AppliedMapContent(styleUrl = styleUrl, points = listOf(contact), lines = emptyList()),
                hasLiveStyle = true,
                hasFramed = true,
            ),
        )
    }

    @Test
    fun aNewStyleReloadsEvenWithUnchangedContent() {
        assertEquals(
            MapContentApplyAction.LOAD_STYLE,
            mapContentApplyAction(
                requested = content(points = listOf(contact), url = otherStyleUrl),
                applied = AppliedMapContent(styleUrl = styleUrl, points = listOf(contact), lines = emptyList()),
                hasLiveStyle = true,
                hasFramed = true,
            ),
        )
    }

    /**
     * The whole failure the fix addresses: contacts land mid-load, the load completes, and a refresh
     * re-reads the identical contacts. Every step must end with those pins on the live style.
     */
    @Test
    fun contactsArrivingMidLoadStillReachTheStyle() {
        val host = ApplyRecorder()
        assertEquals(MapContentApplyAction.LOAD_STYLE, host.pass(content(), hasLiveStyle = false))
        assertEquals(
            MapContentApplyAction.AWAIT_STYLE,
            host.pass(content(points = listOf(contact)), hasLiveStyle = false),
        )
        assertEquals(
            MapContentApplyAction.APPLY_CONTENT,
            host.pass(content(points = listOf(contact)), hasLiveStyle = true),
        )
        assertEquals(listOf(contact), host.applied.points)
        assertEquals(
            MapContentApplyAction.SKIP,
            host.pass(content(points = listOf(contact)), hasLiveStyle = true),
        )
    }

    /**
     * First launch with no saved camera: the style resolves before the database read returns. Framing
     * an empty pin set moves nothing, so the host must not count it as framed — otherwise the pins
     * arrive to a map still on the default world view, with no gesture and no target left to re-frame.
     */
    @Test
    fun anEmptyFirstPassDoesNotCountAsFramed() {
        assertEquals(false, canFrameInitialCamera(initialCamera = null, points = emptyList()))

        val host = ApplyRecorder()
        assertEquals(MapContentApplyAction.LOAD_STYLE, host.pass(content(), hasLiveStyle = false))
        assertEquals(MapContentApplyAction.APPLY_CONTENT, host.pass(content(), hasLiveStyle = true))
        assertEquals(
            MapContentApplyAction.APPLY_CONTENT,
            host.pass(content(points = listOf(contact)), hasLiveStyle = true),
            "the pins arriving must still reach a framing pass",
        )
    }

    /** A saved camera frames on its own, and an unusable one is no more framable than no camera at all. */
    @Test
    fun aSavedCameraFramesWithoutPinsUnlessItIsUnusable() {
        val saved = MapCameraRegion(center = LatLng(37.33, -122.03), spanLatDeg = 0.2, spanLonDeg = 0.2)
        assertEquals(true, canFrameInitialCamera(saved, points = emptyList()))
        assertEquals(true, canFrameInitialCamera(initialCamera = null, points = listOf(contact)))
        assertEquals(
            false,
            canFrameInitialCamera(saved.copy(spanLatDeg = Double.NaN), points = emptyList()),
            "a region the camera would refuse cannot count as framing",
        )
    }
}

/**
 * The host's record rule: a style load records only the URL it requested, and content is recorded only
 * by a pass that had a live style to write it to.
 */
private class ApplyRecorder(private val initialCamera: MapCameraRegion? = null) {
    var applied = AppliedMapContent()
        private set
    private var hasFramed = false

    fun pass(requested: MapContent, hasLiveStyle: Boolean): MapContentApplyAction {
        val action = mapContentApplyAction(requested, applied, hasLiveStyle, hasFramed)
        when (action) {
            MapContentApplyAction.LOAD_STYLE -> applied = applied.copy(styleUrl = requested.styleUrl)
            MapContentApplyAction.APPLY_CONTENT -> {
                applied = AppliedMapContent(requested.styleUrl, requested.points, requested.lines)
                // Latching on a pass that framed nothing is what strands the camera on the world view.
                if (canFrameInitialCamera(initialCamera, requested.points)) hasFramed = true
            }
            MapContentApplyAction.SKIP, MapContentApplyAction.AWAIT_STYLE -> Unit
        }
        return action
    }
}
