import MapLibre

extension MLNCoordinateBounds {
  func overlaps(_ other: MLNCoordinateBounds) -> Bool {
    guard sw.latitude <= other.ne.latitude, ne.latitude >= other.sw.latitude else { return false }
    let fullLongitudeSpan = 360.0
    let east = ne.longitude < sw.longitude ? ne.longitude + fullLongitudeSpan : ne.longitude
    let otherEast = other.ne.longitude < other.sw.longitude
      ? other.ne.longitude + fullLongitudeSpan : other.ne.longitude
    if east - sw.longitude >= fullLongitudeSpan || otherEast - other.sw.longitude >= fullLongitudeSpan {
      return true
    }
    let center = (sw.longitude + east) / 2
    let otherCenter = (other.sw.longitude + otherEast) / 2
    let shift = ((center - otherCenter) / fullLongitudeSpan).rounded() * fullLongitudeSpan
    return sw.longitude <= otherEast + shift && east >= other.sw.longitude + shift
  }
}
