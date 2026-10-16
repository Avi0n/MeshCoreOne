package one.meshcore.shared.map

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PathPreviewSnapshotTest {
    private val arrival = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val other = Uuid.normalized("B0000000-0000-0000-0000-000000000002")

    @Test
    fun sameArrivalAndStyleShareAWidthBucket() {
        val narrower = PathPreviewSnapshot.key(arrival, isDark = false, isOffline = false, widthDp = 100f)
        val nearby = PathPreviewSnapshot.key(arrival, isDark = false, isOffline = false, widthDp = 103f)
        assertEquals(narrower, nearby)
        assertEquals(104, narrower.widthBucket)
    }

    @Test
    fun aDifferentWidthBucketDoesNotShare() {
        val bucket = PathPreviewSnapshot.key(arrival, isDark = false, isOffline = false, widthDp = 100f)
        val next = PathPreviewSnapshot.key(arrival, isDark = false, isOffline = false, widthDp = 92f)
        assertNotEquals(bucket.widthBucket, next.widthBucket)
    }

    @Test
    fun darkOfflineAndArrivalDoNotShare() {
        val light = PathPreviewSnapshot.key(arrival, isDark = false, isOffline = false, widthDp = 160f)
        val dark = PathPreviewSnapshot.key(arrival, isDark = true, isOffline = false, widthDp = 160f)
        val offline = PathPreviewSnapshot.key(arrival, isDark = false, isOffline = true, widthDp = 160f)
        val otherArrival = PathPreviewSnapshot.key(other, isDark = false, isOffline = false, widthDp = 160f)
        assertNotEquals(light, dark)
        assertNotEquals(light, offline)
        assertNotEquals(light, otherArrival)
    }

    @Test
    fun unusableWidthBucketsToZero() {
        assertEquals(0, PathPreviewSnapshot.bucketedWidthDp(0f))
        assertEquals(0, PathPreviewSnapshot.bucketedWidthDp(Float.NaN))
    }
}
