// PortedFrom: MC1Tests/Utilities/MeshCoreURLParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.deeplinks.MeshCoreUriParser
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MapUriOriginalPredicateTest {
    @Test
    fun originalValidCoordinateUsesStrictSourceTolerance() {
        val coordinate = assertNotNull(
            MeshCoreUriParser.parseMap("meshcore://map?lat=37.334900&lon=-122.009020"),
        )
        assertTrue(abs(coordinate.latitude - 37.3349) < 0.000001)
        assertTrue(abs(coordinate.longitude - -122.00902) < 0.000001)
    }

    @Test
    fun originalSouthernLatitudeBeyondTheBoundIsRejected() {
        assertNull(MeshCoreUriParser.parseMap("meshcore://map?lat=-90.001&lon=0.0"))
    }

    @Test
    fun originalChannelRouteIsNotAMapEvenWithCoordinates() {
        assertNull(MeshCoreUriParser.parseMap("meshcore://channel/add?lat=10.0&lon=10.0"))
    }
}
