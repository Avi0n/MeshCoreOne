import MapKit
import SwiftUI

/// Hop count and drawn-path distance in place of the map sheet's navigation title.
/// A question-mark control appears when hops could not be placed, so distance may be shorter or omitted.
struct PathDistanceBanner: View {
  private static let horizontalPadding: CGFloat = 16
  private static let verticalPadding: CGFloat = 8

  let hopCount: Int
  let totalPathDistance: CLLocationDistance?
  var isDistanceIncomplete = false

  var body: some View {
    HStack(spacing: 4) {
      Text(L10n.Contacts.Contacts.Trace.Map.hops(hopCount))
        .contentTransition(.identity)
      if let distance = totalPathDistance {
        Text("•")
        Text(
          Measurement(value: distance, unit: UnitLength.meters),
          format: .measurement(width: .abbreviated, usage: .road)
        )
        .contentTransition(.identity)
      }
      if isDistanceIncomplete {
        FallbackMatchIndicatorView(
          accessibilityLabel: L10n.Chats.Chats.Path.Distance.incomplete,
          accessibilityHint: L10n.Chats.Chats.Path.Distance.incompleteExplanation,
          title: L10n.Chats.Chats.Path.Distance.incompleteTitle,
          explanation: L10n.Chats.Chats.Path.Distance.incompleteExplanation
        )
      }
    }
    .font(.subheadline.weight(.medium))
    .padding(.horizontal, Self.horizontalPadding)
    .padding(.vertical, Self.verticalPadding)
    .geometryGroup()
    .liquidGlass(in: .capsule)
    .transaction { $0.animation = nil }
  }
}
