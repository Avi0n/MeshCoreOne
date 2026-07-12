import SwiftUI

/// A Label whose icon renders in the accent color.
/// Use in NavigationSplitView sidebars where automatic icon tinting is suppressed.
struct TintedLabel: View {
  let title: String
  let systemImage: String

  init(_ title: String, systemImage: String) {
    self.title = title
    self.systemImage = systemImage
  }

  var body: some View {
    Label {
      Text(title)
    } icon: {
      Image(systemName: systemImage)
        .foregroundStyle(.tint)
    }
  }
}
