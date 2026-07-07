import SwiftUI
import UIKit

/// Reaches the `UICollectionView` backing `MessagingUI.TiledView`, which exposes
/// no hook for `keyboardDismissMode`. Inserts an invisible marker, walks up to a
/// shared ancestor, then scans its subtree for the collection view.
///
/// The collection view is built after the marker moves to the window, so the
/// search retries across a few run-loop turns until it succeeds.
struct CollectionViewIntrospector: UIViewRepresentable {
  let configure: (UICollectionView) -> Void

  func makeUIView(context: Context) -> MarkerView {
    MarkerView(configure: configure)
  }

  func updateUIView(_ uiView: MarkerView, context: Context) {
    uiView.scheduleSearch()
  }

  final class MarkerView: UIView {
    private let configure: (UICollectionView) -> Void
    private weak var configured: UICollectionView?
    private var attemptsRemaining = 0

    init(configure: @escaping (UICollectionView) -> Void) {
      self.configure = configure
      super.init(frame: .zero)
      isHidden = true
      isUserInteractionEnabled = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func didMoveToWindow() {
      super.didMoveToWindow()
      if window != nil { scheduleSearch() }
    }

    func scheduleSearch() {
      attemptsRemaining = 10
      search()
    }

    private func search() {
      if let collectionView = findCollectionView(), collectionView !== configured {
        configured = collectionView
        configure(collectionView)
        return
      }
      guard attemptsRemaining > 0, window != nil else { return }
      attemptsRemaining -= 1
      DispatchQueue.main.async { [weak self] in self?.search() }
    }

    private func findCollectionView() -> UICollectionView? {
      var ancestor = superview
      while let current = ancestor {
        if let match = current.firstCollectionViewInSubtree() { return match }
        ancestor = current.superview
      }
      return nil
    }
  }
}

private extension UIView {
  func firstCollectionViewInSubtree() -> UICollectionView? {
    if let collectionView = self as? UICollectionView { return collectionView }
    for subview in subviews {
      if let match = subview.firstCollectionViewInSubtree() { return match }
    }
    return nil
  }
}
