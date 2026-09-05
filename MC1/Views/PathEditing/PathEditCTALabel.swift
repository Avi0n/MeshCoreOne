import SwiftUI

/// Full-width, center-aligned icon+text label for the Add Hop / routing CTA
/// buttons, shared by the contact path editor and the trace path builder so the
/// two read identically. The explicit icon frame keeps `.borderedProminent` from
/// collapsing the SF Symbol to zero width; the spacers center the intrinsic-width
/// pair within the stretched frame.
struct PathEditCTALabel: View {
  let title: String
  let systemImage: String

  @ScaledMetric(relativeTo: .body) private var ctaIconSpacing = PathEditMetrics.ctaIconSpacing
  @ScaledMetric(relativeTo: .body) private var ctaIconSize = PathEditMetrics.ctaIconSize

  var body: some View {
    HStack(spacing: ctaIconSpacing) {
      Spacer()
      Image(systemName: systemImage)
        .font(.body.weight(.semibold))
        .frame(width: ctaIconSize, height: ctaIconSize)
      Text(title)
        .font(.body.weight(.semibold))
      Spacer()
    }
    .frame(maxWidth: .infinity)
  }
}
