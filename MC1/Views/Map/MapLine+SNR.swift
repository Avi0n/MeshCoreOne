import CoreLocation
import Foundation
import MC1Services

extension MapLine.LineStyle {
  /// Maps an SNR value to a trace line style. `SNRQuality` owns the thresholds, so no bare
  /// SNR literals are re-encoded here.
  static func forSNR(_ snr: Double?) -> MapLine.LineStyle {
    switch SNRQuality(snr: snr) {
    case .excellent, .good: .traceGood
    case .fair: .traceMedium
    case .poor: .traceWeak
    case .unknown: .traceUntraced
    }
  }
}

extension MapLine {
  /// Builds the midpoint badge label shared by the trace and neighbor SNR maps, of the form
  /// "<distance> · <snr> dB", with the `dB` unit routed through `L10n`. The space before the unit
  /// is composed here so whitespace-trimming tooling can't strip it out of the localized value.
  static func snrBadgeText(distance: Double, snr: Double) -> String {
    let distFormatted = Measurement(value: distance, unit: UnitLength.meters)
      .formatted(.measurement(width: .abbreviated, usage: .road))
    let snrFormatted = snr.formatted(.number.precision(.fractionLength(1)))
    return "\(distFormatted) · \(snrFormatted) \(L10n.RemoteNodes.RemoteNodes.Status.snrBadgeUnit)"
  }

  /// The midpoint distance/SNR badge pin for the link between two coordinates, shared by the trace
  /// and neighbor SNR maps. Callers pass `id` so the trace map keeps its deterministic
  /// `UUID(hopIndex:)` for diffing while the neighbor map mints a fresh one.
  static func snrBadge(
    id: UUID,
    from: CLLocationCoordinate2D,
    to: CLLocationCoordinate2D,
    snr: Double
  ) -> MapPoint {
    let distance = CLLocation(latitude: from.latitude, longitude: from.longitude)
      .distance(from: CLLocation(latitude: to.latitude, longitude: to.longitude))
    return MapPoint(
      id: id,
      coordinate: midpoint(from: from, to: to),
      pinStyle: .badge,
      label: nil,
      isClusterable: false,
      hopIndex: nil,
      badgeText: snrBadgeText(distance: distance, snr: snr)
    )
  }

  /// Geographic midpoint of two coordinates, shifting one longitude by 360° before averaging when
  /// the pair straddles the antimeridian so the badge lands between them rather than on the
  /// opposite hemisphere.
  private static func midpoint(
    from: CLLocationCoordinate2D,
    to: CLLocationCoordinate2D
  ) -> CLLocationCoordinate2D {
    var lon1 = from.longitude
    var lon2 = to.longitude
    if abs(lon1 - lon2) > 180 {
      if lon1 < lon2 { lon1 += 360 } else { lon2 += 360 }
    }
    var midLongitude = (lon1 + lon2) / 2
    if midLongitude > 180 { midLongitude -= 360 }
    return CLLocationCoordinate2D(
      latitude: (from.latitude + to.latitude) / 2,
      longitude: midLongitude
    )
  }
}
