import UIKit

final class ChatKeyboardLayoutObserver: UIView {
  private enum Constants {
    static let markerHeight: CGFloat = 1
    static let settledSampleCount = 3
  }

  var isInteractivePopActive: @MainActor () -> Bool = { false }
  var onLiftChange: @MainActor (CGFloat) -> Void = { _ in }
  var onWindowAttachment: @MainActor () -> Void = {}
  private let marker = UIView()
  private var markerTop: NSLayoutConstraint?
  private var displayLink: CADisplayLink?
  private var isActive = false
  private var publishedLift: CGFloat = 0
  private var previousEdge: CGFloat?
  private var settledSamples = 0
  private var transitionDeadline: CFTimeInterval = 0

  override init(frame: CGRect) {
    super.init(frame: frame)
    isUserInteractionEnabled = false
    accessibilityElementsHidden = true
    marker.translatesAutoresizingMaskIntoConstraints = false
    addSubview(marker)
    NSLayoutConstraint.activate([
      marker.leadingAnchor.constraint(equalTo: leadingAnchor),
      marker.trailingAnchor.constraint(equalTo: trailingAnchor),
      marker.heightAnchor.constraint(equalToConstant: Constants.markerHeight),
    ])
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
    guard let guide else {
      markerTop?.isActive = false
      markerTop = nil
      stopSampling()
      return
    }
    if markerTop?.secondItem as? UIKeyboardLayoutGuide !== guide {
      markerTop?.isActive = false
      markerTop = marker.topAnchor.constraint(equalTo: guide.topAnchor)
    }
    markerTop?.isActive = true
    wake()
  }

  func reconnectKeyboardLayoutGuide() {
    guard markerTop?.isActive == true else { return }
    markerTop?.isActive = false
    markerTop?.isActive = true
    wake()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    wake()
  }

  func setActive(_ active: Bool) {
    guard active != isActive else { return }
    isActive = active
    if active {
      wake()
    } else {
      stopSampling()
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
    guard isActive, window != nil, markerTop?.isActive == true, displayLink == nil else { return }
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
    guard isActive, markerTop?.isActive == true, let window else {
      stopSampling()
      return
    }
    let now = CACurrentMediaTime()
    let modelEdge = marker.convert(.zero, to: window).y
    let threshold = ChatKeyboardLift.liftChangeThreshold
    guard let guide = markerTop?.secondItem as? UIKeyboardLayoutGuide,
          let owner = guide.owningView, owner.window === window else { return }
    let guideEdge = owner.convert(guide.layoutFrame, to: window).minY
    guard abs(modelEdge - guideEdge) <= threshold else {
      previousEdge = nil
      settledSamples = 0
      if now >= transitionDeadline { stopSampling() }
      return
    }
    // The model may contain an endpoint before the first frame has rendered.
    guard let presentation = marker.layer.presentation(),
          let windowPresentation = window.layer.presentation() else { return }
    let edge = presentation.convert(.zero, to: windowPresentation).y
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
