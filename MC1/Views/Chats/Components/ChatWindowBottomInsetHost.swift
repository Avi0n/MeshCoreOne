import UIKit

/// The chrome reads this view's height for the keyboard offset.
final class ChatWindowBottomInsetHost: UIView {
  var reservedHeight: CGFloat = 0 {
    didSet {
      guard reservedHeight != oldValue else { return }
      invalidateIntrinsicContentSize()
    }
  }

  override var intrinsicContentSize: CGSize {
    CGSize(width: UIView.noIntrinsicMetric, height: reservedHeight)
  }
}
