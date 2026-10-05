import MapKit
import SwiftUI

/// Drawn-path distance over a path map, with hop count unless the host already shows hops.
/// A question-mark control appears when hops could not be placed, so distance may be shorter or omitted.
struct PathDistanceBanner: View {
  private static let horizontalPadding: CGFloat = 16
  private static let verticalPadding: CGFloat = 8

  var hopCount: Int = 0
  let totalPathDistance: CLLocationDistance?
  var isDistanceIncomplete = false
  var showsHopCount = true

  var body: some View {
    HStack(spacing: 4) {
      if showsHopCount {
        Text(hopCountText)
          .contentTransition(.identity)
        if totalPathDistance != nil {
          Text("•")
        }
      }
      if let distance = totalPathDistance {
        Text(Self.distanceText(distance))
          .contentTransition(.identity)
      }
      if isDistanceIncomplete {
        FallbackMatchIndicatorView(
          accessibilityLabel: incompleteAccessibilityLabel,
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

  var spokenTexts: [String] {
    var texts: [String] = []
    if showsHopCount {
      texts.append(hopCountText)
    }
    if let totalPathDistance {
      texts.append(Self.distanceText(totalPathDistance))
    }
    if isDistanceIncomplete {
      texts.append(incompleteAccessibilityLabel)
    }
    return texts
  }

  private var hopCountText: String {
    L10n.Contacts.Contacts.Trace.Map.hops(hopCount)
  }

  private var incompleteAccessibilityLabel: String {
    L10n.Chats.Chats.Path.Distance.incomplete
  }

  private static func distanceText(_ meters: CLLocationDistance) -> String {
    Measurement(value: meters, unit: UnitLength.meters)
      .formatted(.measurement(width: .abbreviated, usage: .road))
  }
}
