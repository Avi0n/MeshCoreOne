import SwiftUI
import UIKit

/// Wraps `UIActivityViewController` so a share sheet can be presented programmatically
/// from SwiftUI state. `ShareLink` cannot be triggered after asynchronous work, so the
/// debug-log export drives this via `.sheet(item:)` once the file is generated.
struct ActivityView: UIViewControllerRepresentable {
  let activityItems: [Any]

  func makeUIViewController(context: Context) -> UIActivityViewController {
    UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
  }

  func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
