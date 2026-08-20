import SwiftUI
import UIKit

/// Lets the user pan and zoom a picked image within a circular guide before it's
/// saved as a contact's profile picture, and crops it down to just that region.
struct AvatarCropView: View {
  let image: UIImage
  let onCancel: () -> Void
  let onComplete: (UIImage) -> Void

  @Environment(\.horizontalSizeClass) private var horizontalSizeClass

  @GestureState private var dragTranslation: CGSize = .zero
  @GestureState private var pinchDelta: CGFloat = 1

  @State private var geometry = AvatarCropGeometry(
    cropSize: AvatarCropGeometry.compactMaxCropSize,
    imageSize: .zero
  )

  private let guideLineWidth: CGFloat = 2
  private let dimOpacity = 0.5

  var body: some View {
    NavigationStack {
      GeometryReader { proxy in
        let cropSize = AvatarCropGeometry.cropSize(
          in: proxy.size,
          isRegularWidth: horizontalSizeClass == .regular
        )
        canvas(cropSize: cropSize)
          .frame(maxWidth: .infinity, maxHeight: .infinity)
          .onChange(of: cropSize) { _, newSize in
            applyCropSize(newSize)
          }
          .onAppear {
            applyCropSize(cropSize)
          }
      }
      .background(Color.black.ignoresSafeArea())
      .navigationTitle(L10n.Contacts.Contacts.Detail.Avatar.Crop.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Contacts.Contacts.Common.cancel, action: onCancel)
        }
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Contacts.Contacts.Detail.Avatar.Crop.choose) {
            confirmCrop()
          }
        }
      }
      .toolbarBackground(.hidden, for: .navigationBar)
      .tint(.white)
    }
    .onAppear {
      geometry.imageSize = image.size
    }
  }

  private func canvas(cropSize: CGFloat) -> some View {
    let resolved = resolvedGeometry(cropSize: cropSize)
    let live = resolved.liveTransform(pinchDelta: pinchDelta, dragTranslation: dragTranslation)
    let panStep = cropSize * AvatarCropGeometry.panStepFraction

    return ZStack {
      Image(uiImage: image)
        .resizable()
        .scaledToFill()
        .frame(width: resolved.baseDisplaySize.width, height: resolved.baseDisplaySize.height)
        .scaleEffect(live.scale)
        .offset(x: live.offset.width, y: live.offset.height)
        .allowsHitTesting(false)

      Circle()
        .strokeBorder(Color.white, lineWidth: guideLineWidth)
        .frame(width: cropSize, height: cropSize)
        .allowsHitTesting(false)

      dimmingMask(cropSize: cropSize)
        .allowsHitTesting(false)
    }
    .contentShape(Rectangle())
    .gesture(dragGesture(cropSize: cropSize))
    .simultaneousGesture(magnificationGesture(cropSize: cropSize))
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(L10n.Contacts.Contacts.Detail.Avatar.Crop.preview)
    .accessibilityHint(L10n.Contacts.Contacts.Detail.Avatar.Crop.previewHint)
    .accessibilityAdjustableAction { direction in
      switch direction {
      case .increment:
        geometry.applyZoomStep(AvatarCropGeometry.zoomStep)
      case .decrement:
        geometry.applyZoomStep(-AvatarCropGeometry.zoomStep)
      default:
        break
      }
    }
    .accessibilityAction(named: L10n.Contacts.Contacts.Detail.Avatar.Crop.moveUp) {
      geometry.applyPan(width: 0, height: -panStep)
    }
    .accessibilityAction(named: L10n.Contacts.Contacts.Detail.Avatar.Crop.moveDown) {
      geometry.applyPan(width: 0, height: panStep)
    }
    .accessibilityAction(named: L10n.Contacts.Contacts.Detail.Avatar.Crop.moveLeft) {
      geometry.applyPan(width: -panStep, height: 0)
    }
    .accessibilityAction(named: L10n.Contacts.Contacts.Detail.Avatar.Crop.moveRight) {
      geometry.applyPan(width: panStep, height: 0)
    }
  }

  /// A full-bleed dark scrim with a circular window cut out over the crop guide,
  /// drawn with an even-odd fill so the two shapes combine into a single hole-punched path.
  private func dimmingMask(cropSize: CGFloat) -> some View {
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
      .fill(Color.black.opacity(dimOpacity), style: FillStyle(eoFill: true))
    }
  }

  private func dragGesture(cropSize: CGFloat) -> some Gesture {
    DragGesture()
      .updating($dragTranslation) { value, state, _ in
        state = value.translation
      }
      .onEnded { value in
        commitLiveTransform(cropSize: cropSize, pinchDelta: pinchDelta, dragTranslation: value.translation)
      }
  }

  private func magnificationGesture(cropSize: CGFloat) -> some Gesture {
    MagnifyGesture()
      .updating($pinchDelta) { value, state, _ in
        state = value.magnification
      }
      .onEnded { value in
        commitLiveTransform(cropSize: cropSize, pinchDelta: value.magnification, dragTranslation: dragTranslation)
      }
  }

  private func confirmCrop() {
    let live = resolvedGeometry(cropSize: geometry.cropSize)
      .liveTransform(pinchDelta: pinchDelta, dragTranslation: dragTranslation)
    var snapshot = resolvedGeometry(cropSize: geometry.cropSize)
    snapshot.scale = live.scale
    snapshot.offset = live.offset
    onComplete(croppedImage(snapshot))
  }

  /// Draws via `UIImage.draw(in:)` so EXIF orientation matches the on-screen preview.
  private func croppedImage(_ snapshot: AvatarCropGeometry) -> UIImage {
    let outputSide = AvatarCropGeometry.outputSide
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let renderer = UIGraphicsImageRenderer(
      size: CGSize(width: outputSide, height: outputSide),
      format: format
    )
    return renderer.image { _ in
      image.draw(in: snapshot.imageDrawRect(outputSide: outputSide))
    }
  }

  private func resolvedGeometry(cropSize: CGFloat) -> AvatarCropGeometry {
    var resolved = geometry
    resolved.cropSize = cropSize
    resolved.imageSize = image.size
    return resolved
  }

  private func commitLiveTransform(cropSize: CGFloat, pinchDelta: CGFloat, dragTranslation: CGSize) {
    let live = resolvedGeometry(cropSize: cropSize)
      .liveTransform(pinchDelta: pinchDelta, dragTranslation: dragTranslation)
    geometry.cropSize = cropSize
    geometry.imageSize = image.size
    geometry.scale = live.scale
    geometry.offset = live.offset
  }

  private func applyCropSize(_ cropSize: CGFloat) {
    geometry.cropSize = cropSize
    geometry.imageSize = image.size
    geometry.offset = geometry.clampedOffset(geometry.offset)
  }
}
