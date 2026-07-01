@testable import MC1
@testable import MC1Services
import Testing

@Suite("FresnelZoneRenderer Tests")
struct FresnelZoneRendererTests {
  @Test
  func `ProfileSample computes yTop and yBottom correctly`() {
    let sample = ProfileSample(
      x: 3000,
      yTerrain: 100,
      yLOS: 150,
      fresnelRadius: 20
    )

    #expect(sample.yTop == 170) // 150 + 20
    #expect(sample.yBottom == 130) // 150 - 20
  }

  @Test
  func `isObstructed returns true when terrain above yBottom`() {
    let clear = ProfileSample(x: 0, yTerrain: 100, yLOS: 150, fresnelRadius: 20)
    let obstructed = ProfileSample(x: 0, yTerrain: 140, yLOS: 150, fresnelRadius: 20)

    #expect(clear.isObstructed == false) // 100 < 130 (yBottom)
    #expect(obstructed.isObstructed == true) // 140 > 130 (yBottom)
  }

  @Test
  func `yVisibleBottom clamps to prevent path inversion`() {
    // Normal: terrain below yBottom
    let normal = ProfileSample(x: 0, yTerrain: 100, yLOS: 150, fresnelRadius: 20)
    #expect(normal.yVisibleBottom == 130) // yBottom, not terrain

    // Intrusion: terrain above yBottom but below yTop
    let intrusion = ProfileSample(x: 0, yTerrain: 140, yLOS: 150, fresnelRadius: 20)
    #expect(intrusion.yVisibleBottom == 140) // terrain level

    // Fully blocked: terrain above yTop
    let blocked = ProfileSample(x: 0, yTerrain: 180, yLOS: 150, fresnelRadius: 20)
    #expect(blocked.yVisibleBottom == 170) // clamped to yTop
  }

  @Test
  func `ProfileSample computes inner 60% zone bounds`() {
    let sample = ProfileSample(
      x: 3000,
      yTerrain: 100,
      yLOS: 150,
      fresnelRadius: 20
    )

    // Inner zone is 60% of full radius
    #expect(sample.yTop60 == 162) // 150 + (20 * 0.6)
    #expect(sample.yBottom60 == 138) // 150 - (20 * 0.6)
  }

  @Test
  func `yVisibleBottom60 clamps terrain to inner zone`() {
    // Terrain below inner zone bottom
    let clear = ProfileSample(x: 0, yTerrain: 100, yLOS: 150, fresnelRadius: 20)
    #expect(clear.yVisibleBottom60 == 138) // yBottom60

    // Terrain intrudes into inner zone
    let intrusion = ProfileSample(x: 0, yTerrain: 145, yLOS: 150, fresnelRadius: 20)
    #expect(intrusion.yVisibleBottom60 == 145) // terrain level

    // Terrain above inner zone top
    let blocked = ProfileSample(x: 0, yTerrain: 170, yLOS: 150, fresnelRadius: 20)
    #expect(blocked.yVisibleBottom60 == 162) // clamped to yTop60
  }

  @Test
  func `losHeight interpolates linearly between endpoints`() {
    // Point A at 100m, Point B at 200m, 10km apart
    let heightA = 100.0
    let heightB = 200.0
    let totalDistance = 10000.0

    // At start
    #expect(FresnelZoneRenderer.losHeight(
      atDistance: 0,
      totalDistance: totalDistance,
      heightA: heightA,
      heightB: heightB
    ) == 100)

    // At midpoint
    #expect(FresnelZoneRenderer.losHeight(
      atDistance: 5000,
      totalDistance: totalDistance,
      heightA: heightA,
      heightB: heightB
    ) == 150)

    // At end
    #expect(FresnelZoneRenderer.losHeight(
      atDistance: 10000,
      totalDistance: totalDistance,
      heightA: heightA,
      heightB: heightB
    ) == 200)
  }

  @Test
  func `buildProfileSamples creates samples with correct geometry`() {
    // Create simple elevation profile: flat at 100m, 6km apart
    let elevationProfile = [
      ElevationSample(
        coordinate: .init(latitude: 0, longitude: 0),
        elevation: 100,
        distanceFromAMeters: 0
      ),
      ElevationSample(
        coordinate: .init(latitude: 0, longitude: 0),
        elevation: 100,
        distanceFromAMeters: 3000
      ),
      ElevationSample(
        coordinate: .init(latitude: 0, longitude: 0),
        elevation: 100,
        distanceFromAMeters: 6000
      ),
    ]

    let samples = FresnelZoneRenderer.buildProfileSamples(
      from: elevationProfile,
      pointAHeight: 50, // antenna height above ground
      pointBHeight: 50,
      frequencyMHz: 910,
      refractionK: 1.33 // standard atmosphere
    )

    #expect(samples.count == 3)

    // First sample (at endpoint - no earth bulge)
    #expect(samples[0].x == 0)
    #expect(samples[0].yTerrain == 100) // no bulge at endpoint
    #expect(samples[0].yLOS == 150) // 100 ground + 50 antenna
    #expect(samples[0].fresnelRadius == 0) // at endpoint

    // Middle sample (earth bulge applies: ~0.53m at 3km/3km with k=1.33)
    #expect(samples[1].x == 3000)
    #expect(samples[1].yTerrain > 100.5) // 100 + earth bulge (~0.53m)
    #expect(samples[1].yTerrain < 100.6)
    #expect(samples[1].yLOS == 150) // same height (symmetric)
    #expect(samples[1].fresnelRadius > 20) // ~22m at midpoint

    // Last sample (at endpoint - no earth bulge)
    #expect(samples[2].x == 6000)
    #expect(samples[2].yTerrain == 100) // no bulge at endpoint
    #expect(samples[2].yLOS == 150)
    #expect(samples[2].fresnelRadius == 0) // at endpoint
  }

  @Test
  func `buildProfileSamples handles segment slice correctly (R→B case)`() {
    // Create elevation profile: flat at 100m, 12km total
    // Then take a slice from midpoint (R at 6km) to end (B at 12km)
    let fullProfile = [
      ElevationSample(coordinate: .init(latitude: 0, longitude: 0), elevation: 100, distanceFromAMeters: 0),
      ElevationSample(coordinate: .init(latitude: 0, longitude: 0), elevation: 100, distanceFromAMeters: 3000),
      ElevationSample(coordinate: .init(latitude: 0, longitude: 0), elevation: 100, distanceFromAMeters: 6000), // R
      ElevationSample(coordinate: .init(latitude: 0, longitude: 0), elevation: 100, distanceFromAMeters: 9000),
      ElevationSample(coordinate: .init(latitude: 0, longitude: 0), elevation: 100, distanceFromAMeters: 12000), // B
    ]

    // Take slice from R (index 2) to B (index 4) - simulates R→B segment
    let segmentRB = Array(fullProfile[2...])

    let samples = FresnelZoneRenderer.buildProfileSamples(
      from: segmentRB,
      pointAHeight: 50, // repeater height
      pointBHeight: 50, // B height
      frequencyMHz: 910,
      refractionK: 1.33
    )

    #expect(samples.count == 3)

    // First sample is at R (segment start) - radius must be 0
    #expect(samples[0].x == 6000) // x-coordinate preserved (global)
    #expect(samples[0].fresnelRadius == 0, "Fresnel radius at segment start (R) must be 0")

    // Middle sample at 9km (segment midpoint)
    #expect(samples[1].x == 9000)
    #expect(samples[1].fresnelRadius > 20, "Fresnel radius at segment midpoint should be maximum")

    // Last sample is at B (segment end) - radius must be 0
    #expect(samples[2].x == 12000)
    #expect(samples[2].fresnelRadius == 0, "Fresnel radius at segment end (B) must be 0")
  }
}
