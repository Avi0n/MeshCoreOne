import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Data needed to present the full-screen image viewer
struct ImageViewerData: Identifiable {
  let id = UUID()
  let imageData: Data
  let isGIF: Bool
}

/// Full-screen image viewer with pinch-to-zoom, pan, and share
struct FullScreenImageViewer: View {
  let data: ImageViewerData
  @Environment(\.dismiss) private var dismiss
  @State private var dragOffset: CGFloat = 0

  private var image: UIImage? {
    UIImage(data: data.imageData)
  }

  private var isDragging: Bool {
    dragOffset != 0
  }

  private var backgroundOpacity: Double {
    let progress = min(abs(dragOffset) / 300, 1)
    return 1 - progress * 0.5
  }

  var body: some View {
    NavigationStack {
      ZStack {
        Color.black.opacity(backgroundOpacity).ignoresSafeArea()

        if let image {
          ZoomableImageView(
            image: image,
            onDragChanged: { offset in
              dragOffset = offset
            },
            onDragEnded: { offset, velocity in
              if abs(offset) > 150 || abs(velocity) > 1000 {
                dismiss()
              } else {
                withAnimation(.spring()) {
                  dragOffset = 0
                }
              }
            }
          )
          .offset(y: dragOffset)
          .ignoresSafeArea()
        }
      }
      .toolbar(isDragging ? .hidden : .visible, for: .navigationBar)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Chats.Chats.ImageViewer.close) {
            dismiss()
          }
          .tint(.white)
        }
        ToolbarItem(placement: .primaryAction) {
          ShareLink(item: ShareableImage(data: data.imageData),
                    preview: .init(L10n.Chats.Chats.ImageViewer.share))
            .tint(.white)
        }
      }
      .toolbarBackground(.hidden, for: .navigationBar)
    }
    .accessibilityAction(.magicTap) {
      dismiss()
    }
  }
}

// MARK: - Zoomable Scroll View

/// UIScrollView subclass that sizes its imageView on layout
class ZoomableScrollView: UIScrollView {
  let imageView = UIImageView()

  override func layoutSubviews() {
    super.layoutSubviews()
    guard bounds.width > 0, bounds.height > 0 else { return }
    // Only update at 1x zoom to avoid fighting the zoom transform
    if zoomScale == minimumZoomScale, imageView.frame.size != bounds.size {
      imageView.frame = bounds
      contentSize = bounds.size
    }
  }
}

// MARK: - Zoomable Image View

/// UIViewRepresentable wrapping UIScrollView for native zoom/pan behavior
struct ZoomableImageView: UIViewRepresentable {
  let image: UIImage
  var onDragChanged: ((CGFloat) -> Void)?
  var onDragEnded: ((CGFloat, CGFloat) -> Void)?

  func makeCoordinator() -> Coordinator {
    Coordinator(onDragChanged: onDragChanged, onDragEnded: onDragEnded)
  }

  func makeUIView(context: Context) -> ZoomableScrollView {
    let scrollView = ZoomableScrollView()
    scrollView.minimumZoomScale = 1.0
    scrollView.maximumZoomScale = 5.0
    scrollView.showsVerticalScrollIndicator = false
    scrollView.showsHorizontalScrollIndicator = false
    scrollView.backgroundColor = .clear
    scrollView.delegate = context.coordinator

    let imageView = scrollView.imageView
    imageView.contentMode = .scaleAspectFit
    imageView.clipsToBounds = true
    imageView.image = image

    scrollView.addSubview(imageView)
    context.coordinator.imageView = imageView
    context.coordinator.scrollView = scrollView

    let doubleTap = UITapGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.handleDoubleTap(_:))
    )
    doubleTap.numberOfTapsRequired = 2
    scrollView.addGestureRecognizer(doubleTap)

    let dismissPan = UIPanGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.handleDismissPan(_:))
    )
    dismissPan.delegate = context.coordinator
    scrollView.addGestureRecognizer(dismissPan)
    context.coordinator.dismissPanGesture = dismissPan

    return scrollView
  }

  func updateUIView(_ scrollView: ZoomableScrollView, context: Context) {}

  static func dismantleUIView(_ scrollView: ZoomableScrollView, coordinator: Coordinator) {
    scrollView.imageView.image = nil
  }

  final class Coordinator: NSObject, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    var imageView: UIImageView?
    weak var scrollView: UIScrollView?
    weak var dismissPanGesture: UIPanGestureRecognizer?
    let onDragChanged: ((CGFloat) -> Void)?
    let onDragEnded: ((CGFloat, CGFloat) -> Void)?

    init(onDragChanged: ((CGFloat) -> Void)?, onDragEnded: ((CGFloat, CGFloat) -> Void)?) {
      self.onDragChanged = onDragChanged
      self.onDragEnded = onDragEnded
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
      imageView
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
      guard let imageView else { return }
      let offsetX = max((scrollView.bounds.width - scrollView.contentSize.width) / 2, 0)
      let offsetY = max((scrollView.bounds.height - scrollView.contentSize.height) / 2, 0)
      imageView.center = CGPoint(
        x: scrollView.contentSize.width / 2 + offsetX,
        y: scrollView.contentSize.height / 2 + offsetY
      )
    }

    @objc func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
      guard let scrollView else { return }
      if scrollView.zoomScale > scrollView.minimumZoomScale {
        scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
      } else {
        let location = gesture.location(in: scrollView)
        let zoomScale: CGFloat = 3.0
        let width = scrollView.bounds.width / zoomScale
        let height = scrollView.bounds.height / zoomScale
        let rect = CGRect(
          x: location.x - width / 2,
          y: location.y - height / 2,
          width: width,
          height: height
        )
        scrollView.zoom(to: rect, animated: true)
      }
    }

    // MARK: - Dismiss pan gesture

    @objc func handleDismissPan(_ gesture: UIPanGestureRecognizer) {
      let translation = gesture.translation(in: gesture.view).y
      let velocity = gesture.velocity(in: gesture.view).y

      switch gesture.state {
      case .changed:
        onDragChanged?(translation)
      case .ended, .cancelled:
        onDragEnded?(translation, velocity)
      default:
        break
      }
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
      guard gestureRecognizer === dismissPanGesture,
            let pan = gestureRecognizer as? UIPanGestureRecognizer,
            let scrollView else {
        return true
      }
      guard scrollView.zoomScale <= scrollView.minimumZoomScale else {
        return false
      }
      let velocity = pan.velocity(in: scrollView)
      return abs(velocity.y) > abs(velocity.x)
    }

    func gestureRecognizer(
      _ gestureRecognizer: UIGestureRecognizer,
      shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
    ) -> Bool {
      gestureRecognizer === dismissPanGesture
    }
  }
}

// MARK: - Shareable Image

/// Transferable type for sharing image data.
struct ShareableImage: Transferable {
  let data: Data

  static var transferRepresentation: some TransferRepresentation {
    DataRepresentation(exportedContentType: .image) { item in
      item.data
    }
  }
}
