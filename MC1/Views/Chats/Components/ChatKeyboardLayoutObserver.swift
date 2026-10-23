import UIKit

final class ChatKeyboardLayoutObserver: UIView {
  private enum Constants {
    static let settledSampleCount = 3
  }

  var isInteractivePopActive: @MainActor () -> Bool = { false }
  var onLiftChange: @MainActor (CGFloat) -> Void = { _ in }
  var onWindowAttachment: @MainActor () -> Void = {}
  var onKeyboardFrameChange: @MainActor () -> Void = {}
  private let trackingView = TrackingView()
  private weak var trackedGuide: UIKeyboardLayoutGuide?
  private var trackingConstraints: [NSLayoutConstraint] = []
  private var displayLink: CADisplayLink?
  private var isActive = false
  private var publishedLift: CGFloat = 0
  private var previousEdge: CGFloat?
  private var settledSamples = 0
  private var transitionDeadline: CFTimeInterval = 0

  var keyboardFrameInWindow: CGRect? {
    guard let window, let guide = trackedGuide,
          let owner = guide.owningView, owner.window === window else { return nil }
    return owner.convert(guide.layoutFrame, to: window)
  }

  override init(frame: CGRect) {
    super.init(frame: frame)
    isUserInteractionEnabled = false
    accessibilityElementsHidden = true
    trackingView.translatesAutoresizingMaskIntoConstraints = false
    trackingView.isUserInteractionEnabled = false
    trackingView.accessibilityElementsHidden = true
    trackingView.onLayout = { [weak self] in
      guard let self, isActive, keyboardFrameInWindow != nil else { return }
      onKeyboardFrameChange()
      wake()
    }
    for name in [UIResponder.keyboardWillChangeFrameNotification, UIResponder.keyboardWillHideNotification] {
      NotificationCenter.default.addObserver(self, selector: #selector(keyboardWillChange), name: name, object: nil)
    }
    for name in [UIResponder.keyboardDidChangeFrameNotification, UIResponder.keyboardDidHideNotification] {
      NotificationCenter.default.addObserver(self, selector: #selector(keyboardDidChange), name: name, object: nil)
    }
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    nil
  }

  deinit {
    NotificationCenter.default.removeObserver(self)
    Task { @MainActor [trackingView] in
      trackingView.removeFromSuperview()
    }
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil {
      trackKeyboardLayoutGuide(nil)
    } else {
      onWindowAttachment()
      wake()
    }
  }

  func trackKeyboardLayoutGuide(_ guide: UIKeyboardLayoutGuide?) {
    if trackedGuide !== guide {
      removeTrackingView()
      trackedGuide = guide
    }
    attachTrackingView()
  }

  private func attachTrackingView() {
    guard isActive, let window, let guide = trackedGuide,
          let owner = guide.owningView, owner.window === window,
          let container = owner.superview else {
      removeTrackingView()
      return
    }
    if trackingView.superview !== container {
      removeTrackingView()
      // Sibling placement keeps tracking outside the tab's changing layout engine
      // and leaves the hosting view's subviews under SwiftUI's control.
      container.addSubview(trackingView)
      trackingConstraints = [
        trackingView.topAnchor.constraint(equalTo: owner.topAnchor),
        trackingView.leadingAnchor.constraint(equalTo: owner.leadingAnchor),
        trackingView.trailingAnchor.constraint(equalTo: owner.trailingAnchor),
        trackingView.bottomAnchor.constraint(equalTo: guide.topAnchor),
      ]
      NSLayoutConstraint.activate(trackingConstraints)
    }
    wake()
  }

  private func removeTrackingView() {
    NSLayoutConstraint.deactivate(trackingConstraints)
    trackingConstraints.removeAll()
    trackingView.removeFromSuperview()
    stopSampling()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    attachTrackingView()
  }

  func setActive(_ active: Bool) {
    guard active != isActive else { return }
    isActive = active
    if active {
      attachTrackingView()
    } else {
      removeTrackingView()
      transitionDeadline = 0
      publishedLift = 0
    }
  }

  @objc private func keyboardWillChange(_ notification: Notification) {
    guard isRelevant(notification) else { return }
    let duration = notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double ?? 0
    // Duration only keeps observation alive; the layout guide supplies all movement.
    transitionDeadline = max(transitionDeadline, CACurrentMediaTime() + duration)
    settledSamples = 0
    wake()
  }

  @objc private func keyboardDidChange(_ notification: Notification) {
    guard isRelevant(notification) else { return }
    settledSamples = 0
    wake()
  }

  private func isRelevant(_ notification: Notification) -> Bool {
    guard isActive, let window else { return false }
    if let screen = notification.object as? UIScreen {
      return screen === window.screen
    }
    return true
  }

  private func wake() {
    guard isActive, window != nil, !trackingConstraints.isEmpty, displayLink == nil else { return }
    previousEdge = nil
    settledSamples = 0
    let target = DisplayLinkTarget(host: self)
    let link = CADisplayLink(target: target, selector: #selector(DisplayLinkTarget.tick))
    link.add(to: .main, forMode: .common)
    displayLink = link
  }

  private func stopSampling() {
    displayLink?.invalidate()
    displayLink = nil
    previousEdge = nil
    settledSamples = 0
  }

  fileprivate func sample() {
    guard isActive, !trackingConstraints.isEmpty, let window else {
      stopSampling()
      return
    }
    let now = CACurrentMediaTime()
    let modelEdge = trackingView.convert(CGPoint(x: trackingView.bounds.minX, y: trackingView.bounds.maxY), to: window).y
    let threshold = ChatKeyboardLift.liftChangeThreshold
    guard let frame = keyboardFrameInWindow else { return }
    let guideEdge = frame.minY
    guard abs(modelEdge - guideEdge) <= threshold else {
      previousEdge = nil
      settledSamples = 0
      if now >= transitionDeadline { stopSampling() }
      return
    }
    // The model may contain an endpoint before the first frame has rendered.
    guard let presentation = trackingView.layer.presentation(),
          let windowPresentation = window.layer.presentation() else { return }
    let edge = presentation.convert(CGPoint(x: presentation.bounds.minX, y: presentation.bounds.maxY), to: windowPresentation).y
    let popIsActive = isInteractivePopActive()
    publish(edge: edge, in: window, popIsActive: popIsActive)

    let isSettled = abs(edge - modelEdge) <= threshold
      && previousEdge.map { abs(edge - $0) <= threshold } == true
    settledSamples = isSettled ? settledSamples + 1 : 0
    previousEdge = edge
    if settledSamples >= Constants.settledSampleCount, now >= transitionDeadline, !popIsActive {
      publish(edge: modelEdge, in: window, popIsActive: false, force: true)
      stopSampling()
    }
  }

  private func publish(edge: CGFloat, in window: UIWindow, popIsActive: Bool, force: Bool = false) {
    let keyboardFrame = CGRect(
      x: window.bounds.minX, y: edge,
      width: window.bounds.width, height: max(0, window.bounds.maxY - edge)
    )
    let proposed = ChatKeyboardLift.ownedBottomPadding(
      keyboardFrameInWindow: keyboardFrame,
      windowBounds: window.bounds,
      bottomSafeArea: window.safeAreaInsets.bottom
    )
    let next = ChatKeyboardLift.resolvedLift(
      current: publishedLift, proposed: proposed, isInteractivePopActive: popIsActive
    )
    guard next != publishedLift,
          force || abs(next - publishedLift) > ChatKeyboardLift.liftChangeThreshold else { return }
    publishedLift = next
    onLiftChange(next)
  }

  private final class TrackingView: UIView {
    var onLayout: @MainActor () -> Void = {}

    override func layoutSubviews() {
      super.layoutSubviews()
      onLayout()
    }
  }

  @MainActor
  private final class DisplayLinkTarget: NSObject {
    weak var host: ChatKeyboardLayoutObserver?
    init(host: ChatKeyboardLayoutObserver) {
      self.host = host
    }

    @objc func tick(_ link: CADisplayLink) {
      guard let host else {
        link.invalidate()
        return
      }
      host.sample()
    }
  }
}
