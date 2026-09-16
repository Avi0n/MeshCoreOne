import SwiftUI
import UIKit

/// Snapshot of the selected path; only the expand chip presents the full map.
struct MessagePathPreviewMap: View {
  static let previewHeight: CGFloat = 160
  static let expandChipInnerPadding: CGFloat = 6
  static let expandChipOuterPadding: CGFloat = 8
  static let cornerRadius: CGFloat = 12
  static let expandChipCornerRadius: CGFloat = 6
  static let retryControlPadding: CGFloat = 8

  let image: UIImage?
  let didFail: Bool
  let onExpand: () -> Void
  let onRetry: () -> Void

  var body: some View {
    ZStack(alignment: .topTrailing) {
      mapContent
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityHidden(true)

      expandButton
    }
    .frame(height: Self.previewHeight)
    .overlay(alignment: .topLeading) {
      if didFail {
        retryButton
      }
    }
    .clipShape(.rect(cornerRadius: Self.cornerRadius))
  }

  @ViewBuilder
  private var mapContent: some View {
    if let image {
      Image(uiImage: image)
        .resizable()
        .scaledToFill()
    } else if didFail {
      fallback
    }
  }

  private var fallback: some View {
    ZStack {
      Color(.secondarySystemBackground)
      Image(systemName: "mappin.circle.fill")
        .font(.largeTitle)
        .foregroundStyle(.secondary)
    }
    .accessibilityHidden(true)
  }

  private var expandButton: some View {
    Button(action: onExpand) {
      Image(systemName: "arrow.up.left.and.arrow.down.right")
        .font(.caption.weight(.semibold))
        .padding(Self.expandChipInnerPadding)
        .background(.regularMaterial, in: .rect(cornerRadius: Self.expandChipCornerRadius))
        .contentShape(.rect)
    }
    .buttonStyle(.plain)
    .padding(Self.expandChipOuterPadding)
    .accessibilityLabel(L10n.Chats.Chats.Path.Accessibility.viewOnMap)
  }

  private var retryButton: some View {
    Button(action: onRetry) {
      Image(systemName: "arrow.clockwise.circle.fill")
        .font(.title2)
        .symbolRenderingMode(.hierarchical)
        .foregroundStyle(.primary)
    }
    .buttonStyle(.plain)
    .padding(Self.retryControlPadding)
    .accessibilityLabel(L10n.Map.Map.Preview.RetryButton.accessibilityLabel)
    .accessibilityAddTraits(.isButton)
  }
}
