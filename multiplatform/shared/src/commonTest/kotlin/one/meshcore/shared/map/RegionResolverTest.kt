package one.meshcore.shared.map

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RegionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

/**
 * The region-resolver failure paths (ported from iOS `RegionResolverTests`: unauthorized → nil, nil
 * country → nil) plus the success path and the geocode-timeout cancellation the Swift tests could not
 * cover without a stubbable location service.
 */
class RegionResolverTest {
    private fun resolver(
        location: LocationService,
        geocoder: Geocoder,
        matcher: RegionalAreasMatcher = FakeRegionalAreasMatcher(),
    ) = RegionResolver(location, geocoder, matcher)

    @Test
    fun unauthorizedLocationReturnsNull() = runTest {
        val geocoder = FakeGeocoder(result = GeocodeResult("US", "California", null))
        val result = resolver(
            FakeLocationService(status = LocationAuthorizationStatus.NOT_DETERMINED),
            geocoder,
        ).resolve()

        assertNull(result)
        assertNull(geocoder.lastLocale) // guard returns before the geocoder runs
    }

    @Test
    fun nilCountryCodeReturnsNull() = runTest {
        val result = resolver(
            FakeLocationService(),
            FakeGeocoder(
                result = GeocodeResult(countryCode = null, administrativeArea = "X", subAdministrativeArea = null),
            ),
        ).resolve()

        assertNull(result)
    }

    @Test
    fun locationFailureReturnsNull() = runTest {
        val result = resolver(
            FakeLocationService(locationResult = { throw LocationServiceError.LocationTimeout }),
            FakeGeocoder(result = GeocodeResult("US", "California", null)),
        ).resolve()

        assertNull(result)
    }

    @Test
    fun successPathNormalizesNamesAndBuildsALocationSelection() = runTest {
        val matcher = FakeRegionalAreasMatcher(subdivision = "CO", county = "nassau")
        val geocoder = FakeGeocoder(
            result = GeocodeResult(
                countryCode = "AR",
                administrativeArea = "Córdoba",
                subAdministrativeArea = "Nassau County",
            ),
        )

        val selection = assertNotNull(resolver(FakeLocationService(), geocoder, matcher).resolve())

        assertEquals("AR", selection.countryCode)
        assertEquals("CO", selection.administrativeAreaCode)
        assertEquals("nassau", selection.countyKey)
        assertEquals(RegionSource.LOCATION, selection.source)
        // Names are trimmed, lowercased, diacritic-folded; the county suffix is stripped.
        assertEquals("cordoba", matcher.subdivisionNormalized)
        assertEquals("nassau", matcher.countyNormalized)
        assertEquals("en_US", geocoder.lastLocale)
    }

    @Test
    fun geocodeTimeoutCancelsTheGeocoderAndReturnsNull() = runTest {
        val geocoder = FakeGeocoder(
            onReverse = {
                delay(Duration.INFINITE)
                null
            },
        )

        val result = resolver(FakeLocationService(), geocoder).resolve()

        assertNull(result)
        assertEquals(1, geocoder.cancelCount)
    }
}
