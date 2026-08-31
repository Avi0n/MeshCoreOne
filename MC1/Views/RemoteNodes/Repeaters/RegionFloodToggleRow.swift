import MC1Services
import SwiftUI

struct RegionFloodToggleRow: View {
  enum Layout {
    static let indentPerLevel: CGFloat = 12
    static let maxVisualIndentDepth: Int = 4
    static let maxGutterWidth: CGFloat = 48
    static let spineWidth: CGFloat = 2

    static func visibleDepth(regionDepth: Int, indentPerLevel: CGFloat) -> Int {
      guard regionDepth > 0 else { return 0 }
      let depthCap = min(regionDepth, maxVisualIndentDepth)
      let widthCap = max(1, Int(maxGutterWidth / indentPerLevel))
      return min(depthCap, widthCap)
    }
  }

  let region: RepeaterRegionEntry
  @Binding var isOn: Bool
  let hasChildren: Bool
  let indentPerLevel: CGFloat

  private var visibleDepth: Int {
    Layout.visibleDepth(regionDepth: region.depth, indentPerLevel: indentPerLevel)
  }

  private var displayName: String {
    region.isUnscoped
      ? L10n.RemoteNodes.RemoteNodes.Settings.Regions.allTrafficWildcard
      : region.name
  }

  private var labelFont: Font {
    if !region.isUnscoped, hasChildren {
      return .body.weight(.medium)
    }
    return .body
  }

  private var labelForegroundStyle: Color {
    region.isUnscoped ? .secondary : .primary
  }

  private var accessibilityLabelText: String {
    if region.isUnscoped {
      return L10n.RemoteNodes.RemoteNodes.Settings.Regions.allTraffic
    }
    guard region.parentName != nil else { return region.name }
    let resolvedParent = region.namedParent
      ?? L10n.RemoteNodes.RemoteNodes.Settings.Regions.allTraffic
    return L10n.RemoteNodes.RemoteNodes.Settings.Regions.childOf(region.name, resolvedParent)
  }

  var body: some View {
    let separatorLeadingInset = CGFloat(visibleDepth) * indentPerLevel
    Toggle(isOn: $isOn) {
      HStack(spacing: 0) {
        if visibleDepth > 0 {
          RegionGutterView(depth: visibleDepth, indentPerLevel: indentPerLevel)
            .accessibilityHidden(true)
        }
        Text(displayName)
          .font(labelFont)
          .foregroundStyle(labelForegroundStyle)
      }
    }
    .accessibilityLabel(accessibilityLabelText)
    .accessibilityHint(L10n.RemoteNodes.RemoteNodes.Settings.Regions.floodToggleHint)
    .alignmentGuide(.listRowSeparatorLeading) { dimensions in
      dimensions[.leading] + separatorLeadingInset
    }
  }
}

private struct RegionGutterView: View {
  let depth: Int
  let indentPerLevel: CGFloat

  var body: some View {
    HStack(spacing: 0) {
      ForEach(0..<depth, id: \.self) { _ in
        Rectangle()
          .fill(Color.secondary)
          .frame(width: RegionFloodToggleRow.Layout.spineWidth)
          .frame(width: indentPerLevel, alignment: .leading)
      }
    }
    .frame(maxHeight: .infinity)
  }
}
