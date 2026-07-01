import SwiftUI

/// A trailing spinner plus dim shown while a confirmation-gated delete is in flight for a row.
/// Shared by the conversation and node lists.
struct DeletingRowOverlay: ViewModifier {
  let isDeleting: Bool

  private static let spinnerTrailingInset: CGFloat = 16
  private static let deletingOpacity: Double = 0.5

  func body(content: Content) -> some View {
    content
      .overlay(alignment: .trailing) {
        if isDeleting {
          ProgressView().padding(.trailing, Self.spinnerTrailingInset)
        }
      }
      .opacity(isDeleting ? Self.deletingOpacity : 1)
      .allowsHitTesting(!isDeleting)
      .animation(.easeInOut, value: isDeleting)
  }
}

extension View {
  func deletingRowOverlay(isDeleting: Bool) -> some View {
    modifier(DeletingRowOverlay(isDeleting: isDeleting))
  }
}
