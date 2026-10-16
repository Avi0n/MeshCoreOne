import SwiftUI

/// Warm thank-you after a verified tip or All Themes purchase on Support Development.
struct PurchaseThankYouSheet: View {
  @Environment(\.dismiss) private var dismiss
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize

  private enum Metrics {
    static let contentSpacing: CGFloat = 16
    static let topPadding: CGFloat = 28
    static let horizontalPadding: CGFloat = 24
    static let bottomPadding: CGFloat = 24
    static let heartSize: CGFloat = 44
    static let buttonMinHeight: CGFloat = 44
  }

  var body: some View {
    VStack(spacing: Metrics.contentSpacing) {
      Image(systemName: "heart.fill")
        .font(.system(size: Metrics.heartSize))
        .foregroundStyle(.tint)
        .accessibilityHidden(true)
        .padding(.top, Metrics.topPadding)

      Text(L10n.Settings.Support.ThankYou.title)
        .font(.title2.bold())
        .multilineTextAlignment(.center)
        .accessibilityHeading(.h1)

      Text(L10n.Settings.Support.ThankYou.body)
        .font(.body)
        .foregroundStyle(.secondary)
        .multilineTextAlignment(.center)
        .padding(.horizontal, Metrics.horizontalPadding)

      Spacer(minLength: 0)

      Button {
        dismiss()
      } label: {
        Text(L10n.Localizable.Common.done)
          .font(.headline)
          .frame(maxWidth: .infinity)
          .padding()
      }
      .liquidGlassProminentButtonStyle()
      .frame(minHeight: Metrics.buttonMinHeight)
      .padding(.horizontal, Metrics.horizontalPadding)
      .padding(.bottom, Metrics.bottomPadding)
    }
    .presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.medium])
    .presentationDragIndicator(.visible)
  }
}

#Preview {
  Color.clear.sheet(isPresented: .constant(true)) {
    PurchaseThankYouSheet()
  }
}
