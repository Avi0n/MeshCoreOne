import SwiftUI
import UIKit

/// UIViewRepresentable that renders animated GIF data using UIImageView
struct AnimatedGIFView: UIViewRepresentable {
  let image: UIImage

  func makeUIView(context: Context) -> UIImageView {
    let imageView = UIImageView()
    imageView.contentMode = .scaleAspectFit
    imageView.clipsToBounds = true
    imageView.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
    imageView.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
    imageView.image = image
    return imageView
  }

  func updateUIView(_ imageView: UIImageView, context: Context) {
    imageView.image = image
  }

  static func dismantleUIView(_ imageView: UIImageView, coordinator: ()) {
    imageView.image = nil
  }
}
