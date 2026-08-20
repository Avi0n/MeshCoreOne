import SwiftUI
import UIKit

/// Lets the user pan and zoom a picked image within a circular guide before it's
/// saved as a contact's profile picture, and crops it down to just that region.
struct AvatarCropView: View {
  let image: UIImage
  let onCancel: () -> Void
  let onComplete: (UIImage) -> Void

  /// Side length, in points, of the square crop guide shown on screen.
  private let cropSize: CGFloat = 300

  /// Allowed zoom range, applied both live during a pinch and once it ends.
  private let minScale: CGFloat = 1
  private let maxScale: CGFloat = 4

  @GestureState private var dragTranslation: CGSize = .zero
  @GestureState private var pinchDelta: CGFloat = 1

  @State private var offset: CGSize = .zero
  @State private var scale: CGFloat = 1

  var body: some View {
    NavigationStack {
      ZStack {
        Color.black.ignoresSafeArea()

        Image(uiImage: image)
          .resizable()
          .scaledToFill()
          .frame(width: baseDisplaySize.width, height: baseDisplaySize.height)
          .scaleEffect(clampedScale(scale * pinchDelta))
          .offset(x: offset.width + dragTranslation.width, y: offset.height + dragTranslation.height)
          .frame(width: cropSize, height: cropSize)
          .clipped()
          .contentShape(Rectangle())
          .gesture(dragGesture)
          .simultaneousGesture(magnificationGesture)

        Circle()
          .strokeBorder(Color.white, lineWidth: 2)
          .frame(width: cropSize, height: cropSize)
          .allowsHitTesting(false)

        dimmingMask
          .allowsHitTesting(false)
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
      .navigationTitle(L10n.Contacts.Contacts.Detail.Avatar.Crop.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Contacts.Contacts.Detail.Avatar.Crop.cancel, action: onCancel)
        }
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Contacts.Contacts.Detail.Avatar.Crop.choose) {
            onComplete(croppedImage())
          }
        }
      }
    }
  }

  /// A full-bleed dark scrim with a circular window cut out over the crop guide,
  /// drawn with an even-odd fill so the two shapes combine into a single hole-punched path.
  private var dimmingMask: some View {
    GeometryReader { proxy in
      Path { path in
        path.addRect(CGRect(origin: .zero, size: proxy.size))
        let circleRect = CGRect(
          x: (proxy.size.width - cropSize) / 2,
          y: (proxy.size.height - cropSize) / 2,
          width: cropSize,
          height: cropSize
        )
        path.addEllipse(in: circleRect)
      }
      .fill(Color.black.opacity(0.5), style: FillStyle(eoFill: true))
    }
  }

  /// The image's size, in points, when scaled (via `.scaledToFill`) to just cover the crop square.
  private var baseDisplaySize: CGSize {
    let imageSize = image.size
    guard imageSize.width > 0, imageSize.height > 0 else { return CGSize(width: cropSize, height: cropSize) }
    let fillScale = max(cropSize / imageSize.width, cropSize / imageSize.height)
    return CGSize(width: imageSize.width * fillScale, height: imageSize.height * fillScale)
  }

  private var dragGesture: some Gesture {
    DragGesture()
      .updating($dragTranslation) { value, state, _ in
        state = value.translation
      }
      .onEnded { value in
        offset = clampedOffset(
          CGSize(width: offset.width + value.translation.width, height: offset.height + value.translation.height)
        )
      }
  }

  private var magnificationGesture: some Gesture {
    MagnifyGesture()
      .updating($pinchDelta) { value, state, _ in
        state = value.magnification
      }
      .onEnded { value in
        scale = clampedScale(scale * value.magnification)
        offset = clampedOffset(offset)
      }
  }

  private func clampedScale(_ proposed: CGFloat) -> CGFloat {
    min(max(proposed, minScale), maxScale)
  }

  /// Keeps the displayed image covering the crop square at all times, regardless of pan/zoom.
  private func clampedOffset(_ proposed: CGSize) -> CGSize {
    let displayedSize = CGSize(width: baseDisplaySize.width * scale, height: baseDisplaySize.height * scale)
    let maxOffsetX = max(0, (displayedSize.width - cropSize) / 2)
    let maxOffsetY = max(0, (displayedSize.height - cropSize) / 2)
    return CGSize(
      width: min(max(proposed.width, -maxOffsetX), maxOffsetX),
      height: min(max(proposed.height, -maxOffsetY), maxOffsetY)
    )
  }

  /// Renders the portion of the source image currently visible inside the crop guide.
  ///
  /// Draws via `UIImage.draw(in:)` rather than cropping `cgImage` directly, so that EXIF
  /// orientation (e.g. a portrait photo shot with a rotated sensor) is honored exactly as
  /// it is in the on-screen preview, which uses the same point-space geometry.
  private func croppedImage() -> UIImage {
    let outputSide: CGFloat = 1024
    let renderScale = outputSide / cropSize

    let displayedSize = CGSize(width: baseDisplaySize.width * scale, height: baseDisplaySize.height * scale)
    let imageOrigin = CGPoint(
      x: (cropSize - displayedSize.width) / 2 + offset.width,
      y: (cropSize - displayedSize.height) / 2 + offset.height
    )

    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: outputSide, height: outputSide), format: format)
    return renderer.image { _ in
      let drawRect = CGRect(
        x: imageOrigin.x * renderScale,
        y: imageOrigin.y * renderScale,
        width: displayedSize.width * renderScale,
        height: displayedSize.height * renderScale
      )
      image.draw(in: drawRect)
    }
  }
}
