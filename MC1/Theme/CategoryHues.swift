import Foundation

/// Curated per-category avatar hues (degrees) for a gamut-derived theme, each chosen from the
/// theme's own anchors to look good and stay distinct. The System theme pins full colors via
/// `CategoryAvatarColors` and leaves this `nil`; a gamut theme that also leaves it `nil` falls back
/// to a distinct on-anchor pick.
struct CategoryHues: Equatable {
  let channel: Double
  let repeater: Double
  let room: Double

  func hue(for category: AvatarCategory) -> Double {
    switch category {
    case .channel: channel
    case .repeater: repeater
    case .room: room
    }
  }
}
