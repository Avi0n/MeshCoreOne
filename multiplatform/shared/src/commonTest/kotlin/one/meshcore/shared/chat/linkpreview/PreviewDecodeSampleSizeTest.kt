package one.meshcore.shared.chat.linkpreview

import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewDecodeSampleSizeTest {
    @Test
    fun anEdgeUnderTwiceTheCapStaysFullSize() {
        assertEquals(1, PreviewImageProcessor.sampleSize(width = 1999, height = 100))
        assertEquals(1, PreviewImageProcessor.sampleSize(width = 3999, height = 100))
    }

    @Test
    fun eachDoublingOfTheLongerEdgeDoublesTheSubsample() {
        assertEquals(2, PreviewImageProcessor.sampleSize(width = 4000, height = 100))
        assertEquals(4, PreviewImageProcessor.sampleSize(width = 8000, height = 8000))
    }

    @Test
    fun eitherEdgeCanForceTheSubsample() {
        assertEquals(2, PreviewImageProcessor.sampleSize(width = 100, height = 4000))
    }
}
