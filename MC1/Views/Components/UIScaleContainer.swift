import SwiftUI
import UIKit

/// Render-time zoom for the whole app UI.
///
/// On "Designed for iPad" (iOS apps running on Apple Silicon Macs) Apple disables
/// Dynamic Type entirely: neither the SwiftUI `\.dynamicTypeSize` nor the low-level
/// `\.sizeCategory` environment values scale `Text`/`List` fonts on that runtime.
/// The only way to make the whole UI bigger there is to scale the rendered output.
///
/// The naive `scaleEffect` clips content because the view still lays out at 1.0×
/// while rendering at `scale` — scroll views measure layout space, not render
/// space. The fix is the classic "scale with compensating frame" trick: lay the
/// content out in a frame of `windowSize / scale`, then scale it up by `scale` so
/// the rendered output exactly fills the window. Layout sees the pre-scale size,
/// so scroll metrics and hit targets stay consistent with what is on screen.
///
/// Only active when actually running on a Mac (`isiOSAppOnMac`) with a scale > 1;
/// on iOS the standard Dynamic Type path (`.environment(\.dynamicTypeSize, ...)`)
/// already scales text and `@ScaledMetric` values proportionally, so the view is
/// passed through untouched with no `GeometryReader` layout overhead.
struct UIScaleContainer<Content: View>: View {
  let scale: CGFloat
  @ViewBuilder let content: Content

  private var shouldZoom: Bool {
    ProcessInfo.processInfo.isiOSAppOnMac && scale > 1.001
  }

  var body: some View {
    if shouldZoom {
      GeometryReader { proxy in
        content
          .frame(
            width: proxy.size.width / scale,
            height: proxy.size.height / scale,
            alignment: .topLeading
          )
          .scaleEffect(scale, anchor: .topLeading)
          .frame(
            width: proxy.size.width,
            height: proxy.size.height,
            alignment: .topLeading
          )
          .clipped()
      }
    } else {
      content
    }
  }
}

extension View {
  /// Scales the whole view by `scale` on Mac ("Designed for iPad"); no-op on iOS.
  func uiScale(_ scale: CGFloat) -> some View {
    UIScaleContainer(scale: scale) { self }
  }
}
