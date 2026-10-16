import CoreLocation
import Foundation
@testable import MC1
import Testing

@Suite("LocationReportFormat")
struct LocationReportFormatTests {
  // MARK: - Coordinates

  @Test
  func `Coordinates format to four decimal places with a dotted separator`() {
    let text = LocationReportFormat.coordinates(
      CLLocationCoordinate2D(latitude: 37.784712, longitude: -122.401233)
    )
    #expect(text == "37.7847, -122.4012")
  }

  @Test
  func `Coordinates round to four places rather than truncate`() {
    let text = LocationReportFormat.coordinates(
      CLLocationCoordinate2D(latitude: 37.78475, longitude: -122.40125)
    )
    #expect(text == "37.7848, -122.4013")
  }

  @Test
  func `Zero coordinates format without dropping the pair`() {
    let text = LocationReportFormat.coordinates(
      CLLocationCoordinate2D(latitude: 0, longitude: 0)
    )
    #expect(text == "0.0000, 0.0000")
  }

  // MARK: - Relative time

  @Test
  func `Relative time for a past report is non-empty`() {
    let now = Date(timeIntervalSince1970: 1_000_000)
    let twoHoursAgo = now.addingTimeInterval(-2 * 3600)
    #expect(!LocationReportFormat.relativeTime(for: twoHoursAgo, relativeTo: now).isEmpty)
  }

  // MARK: - Altitude

  @Test
  func `Altitude formats to a non-empty localized length`() {
    // Output unit is locale-dependent (m vs ft); assert only that a value renders.
    #expect(!LocationReportFormat.altitude(42).isEmpty)
  }

  @Test
  func `Sea-level altitude still renders`() {
    #expect(!LocationReportFormat.altitude(0).isEmpty)
  }
}
