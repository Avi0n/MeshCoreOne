import CoreLocation
@testable import MC1
import Testing

@Suite("MapPointClustering")
struct MapPointClusteringTests {
  @Test
  func `enabled keeps clusterable pins in the clusterable bucket`() {
    let contact = Self.point(isClusterable: true)
    let dropped = Self.point(isClusterable: false)
    let result = MapPointClustering.partition(
      [contact, dropped],
      clusteringEnabled: true
    )
    #expect(result.clusterable.map(\.id) == [contact.id])
    #expect(result.fixed.map(\.id) == [dropped.id])
  }

  @Test
  func `disabled moves clusterable pins into the fixed bucket`() {
    let contact = Self.point(isClusterable: true)
    let dropped = Self.point(isClusterable: false)
    let result = MapPointClustering.partition(
      [contact, dropped],
      clusteringEnabled: false
    )
    #expect(result.clusterable.isEmpty)
    #expect(result.fixed.map(\.id) == [contact.id, dropped.id])
  }

  @Test
  func `empty input stays empty in both buckets`() {
    let result = MapPointClustering.partition([], clusteringEnabled: true)
    #expect(result.clusterable.isEmpty)
    #expect(result.fixed.isEmpty)
  }

  private static func point(isClusterable: Bool) -> MapPoint {
    MapPoint(
      id: UUID(),
      coordinate: CLLocationCoordinate2D(latitude: 1, longitude: 2),
      pinStyle: isClusterable ? .contactChat : .droppedPin,
      label: isClusterable ? "A" : nil,
      isClusterable: isClusterable,
      hopIndex: nil,
      badgeText: nil
    )
  }
}
