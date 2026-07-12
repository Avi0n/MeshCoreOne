import SwiftUI

/// Leading inset applied to sidebar content-column rows so their text clears the floating
/// Liquid Glass sidebar while the section background extends underneath it via
/// `backgroundExtensionEffect()`.
let sidebarContentLeadingInset: CGFloat = 16

extension View {
  /// Extends this view's background under the adjacent floating Liquid Glass sidebar on
  /// iOS 26+ by mirroring and blurring its leading-edge pixels into the sidebar's safe-area
  /// region. Below iOS 26 the sidebar is an opaque column and the modifier is omitted.
  ///
  /// Apply only to a static section background, not to fast-scrolling rows: the effect is a
  /// GPU duplicate plus mirror plus blur, and Apple advises using it on a single instance of
  /// background content with consideration of performance.
  @ViewBuilder
  func sidebarBackgroundExtension() -> some View {
    if #available(iOS 26, *) {
      backgroundExtensionEffect()
    } else {
      self
    }
  }
}
