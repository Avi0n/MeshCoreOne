import SwiftUI
import UIKit

extension View {
  func chatBottomChrome(
    canvas: Color,
    @ViewBuilder content: () -> some View
  ) -> some View {
    modifier(ChatBottomChromeModifier(canvas: canvas, chrome: content()))
  }
}

private struct ChatBottomChromeModifier<Chrome: View>: ViewModifier {
  @Environment(\.scenePhase) private var scenePhase
  @State private var lift: CGFloat = 0
  let canvas: Color
  let chrome: Chrome

  func body(content: Content) -> some View {
    Host(
      timeline: content, canvas: canvas, chrome: chrome,
      isActive: scenePhase == .active,
      onLiftChange: { next in
        guard next != lift else { return }
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) { lift = next }
      }
    )
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .ignoresSafeArea([.container, .keyboard], edges: .vertical)
    .environment(\.chatKeyboardLift, scenePhase == .active ? lift : 0)
  }

  private struct Host<Timeline: View>: UIViewControllerRepresentable {
    struct TimelineRoot: View {
      let timeline: Timeline
      var chromeHeight: CGFloat
      var topInset: CGFloat
      let environment: EnvironmentValues

      var body: some View {
        timeline
          .safeAreaInset(edge: .top, spacing: 0) {
            Color.clear.frame(height: topInset).allowsHitTesting(false).accessibilityHidden(true)
          }
          .safeAreaInset(edge: .bottom, spacing: 0) {
            Color.clear.frame(height: chromeHeight).allowsHitTesting(false).accessibilityHidden(true)
          }
          .environment(\.chatKeyboardLift, 0)
          .environment(\.self, environment)
      }
    }

    struct Root: View {
      let canvas: Color
      let chrome: Chrome
      let environment: EnvironmentValues

      var body: some View {
        ChatBottomChrome(canvas: canvas) {
          chrome
        }
        .environment(\.chatKeyboardLift, 0)
        .environment(\.self, environment)
      }
    }

    let timeline: Timeline
    let canvas: Color
    let chrome: Chrome
    let isActive: Bool
    let onLiftChange: @MainActor (CGFloat) -> Void

    func makeUIViewController(context: Context) -> Controller {
      Controller(
        timeline: TimelineRoot(timeline: timeline, chromeHeight: 0, topInset: 0, environment: context.environment),
        root: Root(canvas: canvas, chrome: chrome, environment: context.environment)
      )
    }

    func updateUIViewController(_ controller: Controller, context: Context) {
      controller.onLiftChange = onLiftChange
      controller.isCompact = context.environment.horizontalSizeClass == .compact
      controller.isSceneActive = isActive
      withTransaction(context.transaction) {
        controller.timelineHost.rootView = TimelineRoot(
          timeline: timeline, chromeHeight: controller.chromeHeight, topInset: controller.topInset, environment: context.environment
        )
        controller.hosted.rootView = Root(canvas: canvas, chrome: chrome, environment: context.environment)
      }
      controller.refreshActivity()
    }

    static func dismantleUIViewController(_ controller: Controller, coordinator: Void) {
      controller.deactivate()
    }

    final class Controller: UIViewController, ChatKeyboardPresentationPreparing {
      let timelineHost: UIHostingController<TimelineRoot>
      let hosted: UIHostingController<Root>
      var onLiftChange: @MainActor (CGFloat) -> Void = { _ in }
      var isCompact = false
      var isSceneActive = false

      private let observer = ChatKeyboardLayoutObserver()
      private var keyboardLimit: NSLayoutConstraint?
      private var restingLimit: NSLayoutConstraint!
      private var restingPreference: NSLayoutConstraint!
      private var frozenBottom: NSLayoutConstraint?
      private var popGestures: [UIGestureRecognizer] = []
      private(set) var chromeHeight: CGFloat = 0
      private(set) var topInset: CGFloat = 0
      private var lift: CGFloat = 0
      private var hasQueuedLiftReport = false
      private var isVisible = false

      init(timeline: TimelineRoot, root: Root) {
        timelineHost = UIHostingController(rootView: timeline)
        hosted = UIHostingController(rootView: root)
        super.init(nibName: nil, bundle: nil)
      }

      @available(*, unavailable)
      required init?(coder: NSCoder) {
        nil
      }

      deinit {
        NotificationCenter.default.removeObserver(self)
      }

      override func loadView() {
        view = PassThroughView()
      }

      override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        observer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(observer)
        NSLayoutConstraint.activate([
          observer.topAnchor.constraint(equalTo: view.topAnchor),
          observer.bottomAnchor.constraint(equalTo: view.bottomAnchor),
          observer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
          observer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        observer.isInteractivePopActive = { [weak self] in self?.isPopActive == true }
        observer.onLiftChange = { [weak self] value in
          guard let self else { return }
          lift = value
          queueLiftReport()
        }

        addChild(timelineHost)
        timelineHost.safeAreaRegions = []
        timelineHost.view.backgroundColor = .clear
        timelineHost.view.clipsToBounds = true
        timelineHost.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(timelineHost.view)
        timelineHost.didMove(toParent: self)

        addChild(hosted)
        hosted.safeAreaRegions = []
        hosted.sizingOptions = [.intrinsicContentSize]
        hosted.view.backgroundColor = .clear
        hosted.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hosted.view)
        hosted.didMove(toParent: self)
        restingLimit = hosted.view.bottomAnchor.constraint(lessThanOrEqualTo: view.bottomAnchor)
        restingPreference = hosted.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        restingPreference.priority = .defaultHigh
        NSLayoutConstraint.activate([
          timelineHost.view.topAnchor.constraint(equalTo: view.topAnchor),
          timelineHost.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
          timelineHost.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
          timelineHost.view.bottomAnchor.constraint(equalTo: hosted.view.bottomAnchor),
          hosted.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
          hosted.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
          restingLimit, restingPreference,
        ])
        observer.onWindowAttachment = { [weak self] in self?.refreshActivity() }
        for name in [
          UIResponder.keyboardWillHideNotification, UIResponder.keyboardWillShowNotification,
          UIResponder.keyboardDidShowNotification,
        ] {
          NotificationCenter.default.addObserver(
            self, selector: #selector(keyboardWillChange(_:)), name: name, object: nil
          )
        }
      }

      override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        isVisible = true
        popGestures = [navigationController?.interactivePopGestureRecognizer].compactMap(\.self)
        if #available(iOS 26, *), let gesture = navigationController?.interactiveContentPopGestureRecognizer {
          popGestures.append(gesture)
        }
        for gesture in popGestures {
          gesture.addTarget(self, action: #selector(popChanged))
        }
        refreshActivity()
      }

      override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        deactivate()
      }

      override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let restingInset = isCompact ? 0 : view.safeAreaInsets.bottom
        keyboardLimit?.constant = keyboardHomeInset
        restingLimit.constant = -restingInset
        restingPreference.constant = -restingInset
        let height = hosted.view.bounds.height
        let nextTopInset = view.safeAreaInsets.top
        guard height != chromeHeight || nextTopInset != topInset else { return }
        chromeHeight = height
        topInset = nextTopInset
        var timeline = timelineHost.rootView
        timeline.chromeHeight = height
        timeline.topInset = nextTopInset
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) { timelineHost.rootView = timeline }
        timelineHost.view.layoutIfNeeded()
      }

      func refreshActivity() {
        guard isViewLoaded else { return }
        let active = isSceneActive && isVisible && view.window != nil
        let guide: UIKeyboardLayoutGuide? = if let window = view.window, let rootView = window.rootViewController?.view,
                                               rootView.window === window {
          rootView.keyboardLayoutGuide
        } else {
          nil
        }
        // Descendant guides can lose keyboard geometry during a modal handoff.
        if keyboardLimit?.secondItem as? UIKeyboardLayoutGuide !== guide {
          keyboardLimit?.isActive = false
          keyboardLimit = guide.map {
            hosted.view.bottomAnchor.constraint(lessThanOrEqualTo: $0.topAnchor)
          }
        }
        keyboardLimit?.constant = keyboardHomeInset
        observer.trackKeyboardLayoutGuide(guide)
        observer.setActive(active)
        keyboardLimit?.isActive = active && frozenBottom == nil
        if !active {
          frozenBottom?.isActive = false
          frozenBottom = nil
          hosted.view.endEditing(true)
          lift = 0
          queueLiftReport()
        }
      }

      private var keyboardHomeInset: CGFloat {
        isCompact ? view.window?.safeAreaInsets.bottom ?? 0 : 0
      }

      func deactivate() {
        isVisible = false
        refreshActivity()
        for gesture in popGestures {
          gesture.removeTarget(self, action: #selector(popChanged))
        }
        popGestures.removeAll()
        frozenBottom?.isActive = false
        frozenBottom = nil
      }

      private var isPopActive: Bool {
        popTransitionCoordinator != nil
          || popGestures.contains { $0.state == .began || $0.state == .changed }
      }

      private var popTransitionCoordinator: (any UIViewControllerTransitionCoordinator)? {
        guard let coordinator = navigationController?.transitionCoordinator,
              coordinator.initiallyInteractive, coordinator.presentationStyle == .none else { return nil }
        return coordinator
      }

      func prepareForKeyboardPresentation() {
        guard isSceneActive, isVisible, !isPopActive, frozenBottom == nil,
              keyboardLimit?.isActive == true,
              let guide = keyboardLimit?.secondItem as? UIKeyboardLayoutGuide,
              let window = view.window, guide.owningView?.window === window else { return }
        keyboardLimit?.isActive = false
        keyboardLimit?.isActive = true
        observer.reconnectKeyboardLayoutGuide()
        guide.owningView?.layoutIfNeeded()
      }

      @objc private func keyboardWillChange(_ notification: Notification) {
        synchronizePopFreeze()
      }

      @objc private func popChanged() {
        synchronizePopFreeze()
      }

      private func synchronizePopFreeze() {
        guard isSceneActive, isVisible, view.window != nil else { return }
        if isPopActive, frozenBottom == nil {
          let bottom = hosted.view.layer.presentation()?.frame.maxY ?? hosted.view.frame.maxY
          keyboardLimit?.isActive = false
          let constraint = hosted.view.bottomAnchor.constraint(equalTo: view.topAnchor, constant: bottom)
          constraint.isActive = true
          frozenBottom = constraint
        }
        if let coordinator = popTransitionCoordinator {
          coordinator.animate(alongsideTransition: nil) { [weak self] _ in self?.releasePopFreeze() }
        } else if !isPopActive {
          releasePopFreeze()
        }
      }

      private func releasePopFreeze() {
        frozenBottom?.isActive = false
        frozenBottom = nil
        refreshActivity()
      }

      private func queueLiftReport() {
        guard !hasQueuedLiftReport else { return }
        hasQueuedLiftReport = true
        DispatchQueue.main.async { [weak self] in
          guard let self else { return }
          hasQueuedLiftReport = false
          onLiftChange(lift)
        }
      }

      private final class PassThroughView: UIView {
        override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
          let hit = super.hitTest(point, with: event)
          return hit === self ? nil : hit
        }
      }
    }
  }
}
